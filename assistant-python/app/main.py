"""Material community assistant — FastAPI intent parser (no DB access).

CONTENT_ASSISTANT_PARSER_MODE selects the implementation:
  rules              deterministic keyword parser (app.rules)
  llm                LLM only; a failed call returns 503 so the Java task retries
  llm_with_fallback  (default) LLM; on timeout / API error / invalid output the rules parser answers
                     (source=rules_fallback, fallbackReason=<code>)
The key is read only from CONTENT_ASSISTANT_LLM_API_KEY. Without a key, llm_with_fallback logs one warning at
startup and serves every request from the rules parser (fallbackReason=NO_API_KEY); mode=llm refuses to start.
"""
from __future__ import annotations

import logging
import os
import threading
from collections import Counter
from typing import Callable

from fastapi import FastAPI, Header, HTTPException

from app.contract import (  # noqa: F401  (re-exported for callers that import the models from app.main)
    Clarify, GiveawayCommand, ParseRequest, ParseResult, SearchCommand, SubscribeCommand,
)
from app.guard import override_phrase
from app.llm import QUESTIONS, CallRecord, LlmFailure, LlmParser, LlmSettings, api_key_present
from app.rules import parse_rules

log = logging.getLogger("assistant.parser")
INTERNAL = os.environ.get("CONTENT_ASSISTANT_INTERNAL_TOKEN", "")


class ParserService:
    def __init__(self, settings: LlmSettings, llm: LlmParser | None):
        self.settings = settings
        self.llm = llm
        self._lock = threading.Lock()
        self.counts: Counter[str] = Counter()
        self.listeners: list[Callable[[ParseRequest, ParseResult, CallRecord], None]] = []

    @classmethod
    def from_env(cls) -> "ParserService":
        settings = LlmSettings.from_env()
        if settings.mode == "rules":
            return cls(settings, None)
        if not api_key_present():
            if settings.mode == "llm":
                raise RuntimeError("CONTENT_ASSISTANT_PARSER_MODE=llm requires CONTENT_ASSISTANT_LLM_API_KEY")
            log.warning("CONTENT_ASSISTANT_LLM_API_KEY is not set; llm_with_fallback serves every request from the rules parser")
            return cls(settings, None)
        return cls(settings, LlmParser.from_env(settings))

    def _count(self, *keys: str) -> None:
        with self._lock:
            self.counts.update(keys)

    def parse(self, req: ParseRequest) -> ParseResult:
        if self.settings.mode == "rules":
            result, rec = parse_rules(req), CallRecord(source="rules")
            result.source = "rules"
        elif self.llm is None:
            result, rec = self._fallback(req, CallRecord(source="rules_fallback"), "NO_API_KEY")
        else:
            try:
                result, rec = self.llm.parse(req)
            except LlmFailure as f:
                if self.settings.mode == "llm":
                    self._count("llm_error", f"llm_error:{f.code}")
                    self._log(req, f.record, f.code)
                    raise HTTPException(status_code=503, detail=f"LLM_UNAVAILABLE:{f.code}") from f
                result, rec = self._fallback(req, f.record, f.code)
        if self.settings.mode != "rules":  # rules mode stays the unchanged baseline
            result = self._guard(req, result, rec)
        self._count(f"source:{rec.source}", *([f"fallback:{rec.fallbackReason}"] if rec.fallbackReason else []))
        self._log(req, rec, rec.fallbackReason)
        for fn in self.listeners:
            fn(req, result, rec)
        return result

    def _guard(self, req: ParseRequest, result: ParseResult, rec: CallRecord) -> ParseResult:
        """Extra fuse: an override phrase in the comment turns any command into a refusal (never the reverse)."""
        if not (result.search or result.subscribe or result.giveaway) or not override_phrase(req.body):
            return result
        rec.extra["guard"] = "OVERRIDE_PHRASE"
        self._count("guard:OVERRIDE_PHRASE")
        return ParseResult(intent=result.intent, clarify=Clarify(intent=result.intent, question=QUESTIONS["DROPPED_REJECT"]),
                           unsupportedTools=list(result.unsupportedTools or []), source=result.source,
                           fallbackReason=result.fallbackReason)

    def _fallback(self, req: ParseRequest, rec: CallRecord, reason: str) -> tuple[ParseResult, CallRecord]:
        rec.source, rec.fallbackReason = "rules_fallback", reason
        result = parse_rules(req)
        result.source, result.fallbackReason = "rules_fallback", reason
        return result, rec

    def _log(self, req: ParseRequest, rec: CallRecord, reason: str | None) -> None:
        # ids, timings and counts only: never the prompt or the comment text
        log.info("parse taskId=%s source=%s reason=%s latencyMs=%s in=%s out=%s cacheRead=%s requestId=%s",
                 req.taskId, rec.source, reason, None if rec.latencyMs is None else round(rec.latencyMs),
                 rec.inputTokens, rec.outputTokens, rec.cacheReadTokens, rec.requestId)

    def stats(self) -> dict:
        with self._lock:
            return {"mode": self.settings.mode, "provider": self.settings.provider, "model": self.settings.model,
                    "llmConfigured": self.llm is not None,
                    "counts": dict(self.counts)}


app = FastAPI(title="content-assistant", version="0.2.0")
service = ParserService.from_env()


def _auth(token: str | None) -> None:
    if INTERNAL and token != INTERNAL:
        raise HTTPException(status_code=401, detail="UNAUTHORIZED")


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/v1/parser-stats")
def parser_stats(x_content_internal_token: str | None = Header(default=None)) -> dict:
    _auth(x_content_internal_token)
    return service.stats()


@app.post("/v1/parse", response_model=ParseResult)
def parse(req: ParseRequest, x_content_internal_token: str | None = Header(default=None)) -> ParseResult:
    _auth(x_content_internal_token)
    return service.parse(req)
