"""LLM implementation of /v1/parse: pluggable provider, JSON output, strict Pydantic validation.

Providers (CONTENT_ASSISTANT_LLM_PROVIDER):
- openai_compatible (default this round: DeepSeek, https://api.deepseek.com, model deepseek-flash). Chat Completions
  with response_format={"type": "json_object"} (JSON mode), thinking disabled, temperature 0.
- anthropic: Messages API with output_config.format (JSON schema enforced by the API).
Either way the text is validated against LlmOutput with Pydantic in strict mode. The schema itself is also part of the
system prompt, because JSON mode guarantees valid JSON but not the shape.

Flow: minimal context + comment (as delimited JSON data) -> model -> LlmOutput (validated with Pydantic)
-> deterministic conversion and checks in Python -> ParseResult (same contract as the rules parser).

- The model never writes user-visible text. Clarify questions are rendered here from reason codes, and unsupported
  conditions come from a fixed list. Keywords and tags must be substrings of the comment. A prompt injection can
  only move the result between the enum values the schema allows, and Java still checks every command.
- No tools are sent to the model: it can only produce the output structure.
- Privacy: the model gets the comment text and a minimal context (current time/timezone, isReply, parentKind,
  actorIsAuthor, postLicenseCommercial, enabledTools). It never gets taskId, postId, user or comment ids, or names.
  Logs record ids, timings, token counts and reasons, never the prompt or the comment.
"""
from __future__ import annotations

import json
import logging
import os
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Literal
from zoneinfo import ZoneInfo

import anthropic
import openai
from pydantic import BaseModel, ConfigDict, ValidationError

from app.contract import Clarify, GiveawayCommand, ParseRequest, ParseResult, SearchCommand, SubscribeCommand
from app.llm_prompt import FEW_SHOT_NOW_MS, SYSTEM_PROMPT, few_shot_block

log = logging.getLogger("assistant.parser.llm")
# The SDKs and HTTP clients log full request bodies (prompt + comment) at DEBUG, also when ANTHROPIC_LOG / OPENAI_LOG
# is set. Cap them at WARNING so the comment and the prompt never reach the logs.
for _name in ("anthropic", "openai", "httpx", "httpx2", "httpcore"):
    logging.getLogger(_name).setLevel(logging.WARNING)

API_KEY_ENV = "CONTENT_ASSISTANT_LLM_API_KEY"
MODES = ("rules", "llm", "llm_with_fallback")
TOOLS = ("LIKE", "COMMENT", "FAVORITE", "FOLLOW", "PURCHASE")

# ---------------------------------------------------------------------------------------------------------------------
# Settings


PROVIDERS = ("openai_compatible", "anthropic")
# Defaults per provider. DeepSeek values from https://api-docs.deepseek.com/ (read 2026-11-21): base_url
# https://api.deepseek.com (OpenAI format); deepseek-flash is served by DeepSeek-V4.1-Flash-1017.
PROVIDER_DEFAULTS = {
    "openai_compatible": {"model": "deepseek-flash", "base_url": "https://api.deepseek.com"},
    "anthropic": {"model": "claude-haiku-5-5", "base_url": None},
}


@dataclass(frozen=True)
class LlmSettings:
    mode: str = "llm_with_fallback"
    provider: str = "openai_compatible"
    model: str = "deepseek-flash"
    base_url: str | None = "https://api.deepseek.com"
    timeout_s: float = 10.0
    max_retries: int = 1
    effort: str = "low"  # anthropic only
    thinking: str = "disabled"  # disabled | adaptive (openai_compatible: adaptive = provider's thinking mode on)
    max_tokens: int = 1024

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> "LlmSettings":
        e = os.environ if env is None else env
        mode = e.get("CONTENT_ASSISTANT_PARSER_MODE", cls.mode).strip() or cls.mode
        if mode not in MODES:
            raise ValueError(f"CONTENT_ASSISTANT_PARSER_MODE must be one of {MODES}, got {mode!r}")
        provider = e.get("CONTENT_ASSISTANT_LLM_PROVIDER", cls.provider).strip() or cls.provider
        if provider not in PROVIDERS:
            raise ValueError(f"CONTENT_ASSISTANT_LLM_PROVIDER must be one of {PROVIDERS}, got {provider!r}")
        d = PROVIDER_DEFAULTS[provider]
        thinking = e.get("CONTENT_ASSISTANT_LLM_THINKING", cls.thinking)
        if thinking not in ("disabled", "adaptive"):
            raise ValueError("CONTENT_ASSISTANT_LLM_THINKING must be disabled or adaptive")
        effort = e.get("CONTENT_ASSISTANT_LLM_EFFORT", cls.effort)
        if effort not in ("low", "medium", "high", "xhigh", "max"):
            raise ValueError("CONTENT_ASSISTANT_LLM_EFFORT must be low|medium|high|xhigh|max")
        retries = int(e.get("CONTENT_ASSISTANT_LLM_MAX_RETRIES", cls.max_retries))
        return cls(
            mode=mode,
            provider=provider,
            model=e.get("CONTENT_ASSISTANT_LLM_MODEL", "").strip() or d["model"],
            base_url=e.get("CONTENT_ASSISTANT_LLM_BASE_URL", "").strip() or d["base_url"],
            timeout_s=float(e.get("CONTENT_ASSISTANT_LLM_TIMEOUT_SECONDS", cls.timeout_s)),
            max_retries=max(0, min(1, retries)),  # never more than one retry
            effort=effort,
            thinking=thinking,
            max_tokens=int(e.get("CONTENT_ASSISTANT_LLM_MAX_TOKENS", cls.max_tokens)),
        )

    def worst_case_seconds(self) -> float:
        """Upper bound of one /v1/parse call in LLM mode (each attempt is capped by timeout_s; SDK backoff < 1 s)."""
        return self.timeout_s * (1 + self.max_retries) + 1.0


def api_key_present(env: dict[str, str] | None = None) -> bool:
    e = os.environ if env is None else env
    return bool(e.get(API_KEY_ENV, "").strip())


# ---------------------------------------------------------------------------------------------------------------------
# Output schema (what the model must return)

Reason = Literal[
    "SEARCH_NO_CRITERIA", "SEARCH_CONTRADICTION", "SEARCH_UNSUPPORTED_FILTER", "SEARCH_OUT_OF_SCOPE",
    "SUB_NO_PARENT", "SUB_PARENT_NOT_FEEDBACK",
    "GW_NOT_AUTHOR", "GW_MISSING_CONDITION", "GW_MISSING_DEADLINE", "GW_VAGUE_TIME", "GW_PAST_DEADLINE",
    "GW_WINDOW_AFTER_DEADLINE", "GW_MISSING_WINNERS", "GW_INVALID_WINNERS", "GW_MISSING_PRIZE", "GW_AMBIGUOUS_RULE",
    "GW_CONTRADICTION", "GW_INVALID_K", "GW_UNSUPPORTED_CONDITION", "GW_LICENSE_NOT_GRANTABLE", "GW_OVERREACH",
    "UNRECOGNIZED", "OUT_OF_SCOPE",
]
Unsupported = Literal[
    "转发", "分享", "评论字数", "关注时长", "@好友", "打赏", "粉丝等级", "弹幕", "下载记录", "否定条件", "转售授权",
    "其他帖子的奖品", "作者本人参与", "同一人多次参与", "指定中奖人", "其他不支持的条件",
]


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class LlmSearch(_Strict):
    keywords: list[str]
    tag: str | None
    aspectRatio: Literal["PORTRAIT", "LANDSCAPE", "SQUARE"] | None
    licenseDownload: bool | None
    licenseCommercial: bool | None


class LlmSubscribe(_Strict):
    action: Literal["SUBSCRIBE", "CANCEL"]
    target: Literal["SELF", "OTHERS", "ALL"]


class LlmGiveaway(_Strict):
    conditions: list[Literal["LIKE", "COMMENT", "FAVORITE", "FOLLOW", "PURCHASE"]]
    op: Literal["ALL", "ANY", "AT_LEAST"]
    k: int | None
    deadline: str | None
    windowStart: str | None
    winnerCount: int | None
    rewardLicense: Literal["DOWNLOAD", "COMMERCIAL"] | None


class LlmDropped(_Strict):
    """A requirement in the comment that the output does not carry out. `quote` helps the model be explicit; it is
    never shown to users or logged."""
    kind: Literal["CONFLICT", "UNCLEAR", "UNSUPPORTED", "OVERRIDE", "OVERREACH"]
    quote: str


DROPPED_REJECT_KINDS = {"OVERRIDE", "OVERREACH"}


class LlmOutput(_Strict):
    intent: Literal["SEARCH", "SUBSCRIBE", "GIVEAWAY", "UNKNOWN"]
    decision: Literal["EXECUTE", "CLARIFY", "REJECT"]
    reasons: list[Reason]
    unsupportedConditions: list[Unsupported]
    droppedRequirements: list[LlmDropped]
    search: LlmSearch | None
    subscribe: LlmSubscribe | None
    giveaway: LlmGiveaway | None


def _api_schema(model: type[BaseModel]) -> dict:
    """JSON schema for output_config.format: inline $defs, drop titles; every object closed and fully required."""
    raw = model.model_json_schema()
    defs = raw.pop("$defs", {})

    def walk(node: Any) -> Any:
        if isinstance(node, dict):
            if "$ref" in node:
                return walk(defs[node["$ref"].split("/")[-1]])
            out = {k: walk(v) for k, v in node.items() if k not in ("title", "default")}
            if out.get("type") == "object":
                out["additionalProperties"] = False
                out["required"] = list(out.get("properties", {}).keys())
            return out
        if isinstance(node, list):
            return [walk(x) for x in node]
        return node

    return walk(raw)


OUTPUT_SCHEMA = _api_schema(LlmOutput)

# ---------------------------------------------------------------------------------------------------------------------
# Rendering: the request the model sees

WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]


def _calendar(now: datetime) -> str:
    monday = (now - timedelta(days=now.weekday())).date()
    items = []
    for d in range(14):
        day = (now + timedelta(days=d)).date()
        week = "本周" if (day - monday).days < 7 else ("下周" if (day - monday).days < 14 else "下下周")
        tag = "今天," if d == 0 else ("明天," if d == 1 else "")
        items.append(f"{day.isoformat()} {WEEKDAYS[day.weekday()]}({tag}{week})")
    return "; ".join(items)


def model_context(now_ms: int, tz_name: str, *, actor_is_author: bool, is_reply: bool, parent_kind: str | None,
                  post_license_commercial: bool | None, enabled_tools: list[str]) -> dict:
    tz = ZoneInfo(tz_name)
    now = datetime.fromtimestamp(now_ms / 1000, tz)
    return {
        "now": now.isoformat(timespec="seconds"),
        "weekday": WEEKDAYS[now.weekday()],
        "timezone": tz_name,
        "calendar": _calendar(now),
        "actorIsAuthor": actor_is_author,
        "isReply": is_reply,
        "parentKind": parent_kind,
        "postLicenseCommercial": post_license_commercial,
        "enabledTools": enabled_tools,
    }


def render_input(context: dict, comment: str) -> str:
    # The comment is a JSON string inside its own tag; "<" is escaped so the text cannot close the tag.
    body = json.dumps(comment, ensure_ascii=False).replace("<", "\\u003c").replace(">", "\\u003e")
    return f"<context>{json.dumps(context, ensure_ascii=False)}</context>\n<comment_json>{body}</comment_json>"


def _few_shot_input(overrides: dict, text: str) -> str:
    ctx = model_context(
        FEW_SHOT_NOW_MS, "Asia/Shanghai",
        actor_is_author=overrides.get("actorIsAuthor", False),
        is_reply="parentKind" in overrides,
        parent_kind=overrides.get("parentKind"),
        post_license_commercial=overrides.get("postLicenseCommercial", True if overrides.get("actorIsAuthor") else None),
        enabled_tools=list(TOOLS),
    )
    return render_input(ctx, text)


SYSTEM_TEXT = (SYSTEM_PROMPT + "\n\n# 输出的 JSON Schema（必须严格符合，所有字段都要出现）\n"
               + json.dumps(OUTPUT_SCHEMA, ensure_ascii=False, separators=(",", ":"))
               + "\n\n" + few_shot_block(_few_shot_input))


def request_context(req: ParseRequest) -> dict:
    c = req.context or {}
    now_ms = c.get("nowMs")
    if not isinstance(now_ms, int):
        now_ms = int(time.time() * 1000)
    tz = c.get("timezone") if isinstance(c.get("timezone"), str) else "Asia/Shanghai"
    try:
        ZoneInfo(tz)
    except Exception:
        tz = "Asia/Shanghai"
    lic = c.get("postLicenseCommercial")
    tools = c.get("enabledTools")
    return model_context(
        now_ms, tz,
        actor_is_author=bool(req.actorIsAuthor),
        is_reply=req.parentCommentId is not None,
        parent_kind=c.get("parentKind") if req.parentCommentId is not None else None,
        post_license_commercial=lic if isinstance(lic, bool) else None,
        enabled_tools=[t for t in tools if t in TOOLS] if isinstance(tools, list) else list(TOOLS),
    ) | {"_nowMs": now_ms}


# ---------------------------------------------------------------------------------------------------------------------
# Conversion LlmOutput -> ParseResult (deterministic; mirrors the checks Java repeats before any side effect)

QUESTIONS: dict[str, str] = {
    "SEARCH_NO_CRITERIA": "请补充关键词、标签或画幅/许可条件。",
    "SEARCH_CONTRADICTION": "搜索条件互相矛盾，请确认要哪一个。",
    "SEARCH_UNSUPPORTED_FILTER": "目前只能按关键词、标签、画幅和授权查找，不支持分辨率、时长、上传时间、排序或排除条件，请调整后再试。",
    "SEARCH_OUT_OF_SCOPE": "只能搜索公开可见的素材。",
    "SUB_NO_PARENT": "请在目标反馈评论下回复「@助手 蹲这条的修改结果」。",
    "SUB_PARENT_NOT_FEEDBACK": "只能订阅带素材版本的反馈评论，请在目标反馈下回复「@助手 蹲这条的修改结果」。",
    "GW_NOT_AUTHOR": "只有作者可以发起抽奖。",
    "GW_MISSING_CONDITION": "请说明参与条件（点赞/评论/收藏/关注/购买）。",
    "GW_MISSING_DEADLINE": "请给出明确的截止时间，例如“1月20日晚上8点”。",
    "GW_VAGUE_TIME": "截止时间不够具体，请给出明确的日期和时刻。",
    "GW_PAST_DEADLINE": "截止时间已经过去，请给出将来的时间。",
    "GW_WINDOW_AFTER_DEADLINE": "开始参与的时间晚于截止时间，请确认。",
    "GW_MISSING_WINNERS": "请说明中奖人数。",
    "GW_INVALID_WINNERS": "中奖人数需要在 1 到 100 之间。",
    "GW_MISSING_PRIZE": "请说明奖品：送最新版的下载资格，还是商用授权？",
    "GW_AMBIGUOUS_RULE": "参与规则有歧义（例如「选两项」是指定两项都满足，还是任意满足两项），请确认。",
    "GW_CONTRADICTION": "参与条件互相矛盾，请确认。",
    "GW_INVALID_K": "「至少满足 N 项」的 N 超过了列出的条件数，请确认。",
    "GW_UNSUPPORTED_CONDITION": "包含暂不支持的条件，请去掉后重新发起。",
    "GW_LICENSE_NOT_GRANTABLE": "本素材不允许商用，不能发放商用授权。",
    "GW_OVERREACH": "该要求超出抽奖规则允许的范围（如指定中奖人、批量发放、其他帖子的奖品、作者参与或同一人多次参与），不能创建。",
    "UNRECOGNIZED": "请说明要找素材、蹲改进还是抽奖。",
    "DROPPED_CLARIFY": "评论里的要求前后不一致、有改口或不够明确，助手不会只执行其中一部分。请确认后重新发一条完整的指令。",
    "DROPPED_REJECT": "评论中包含试图改变助手规则或超出权限的要求，本次不执行。",
    "OUT_OF_SCOPE": "助手目前只支持找素材、蹲改进和作者抽奖。",
}
DEFAULT_REASON = {"SEARCH": "SEARCH_NO_CRITERIA", "SUBSCRIBE": "SUB_NO_PARENT", "GIVEAWAY": "GW_MISSING_CONDITION",
                  "UNKNOWN": "UNRECOGNIZED"}


class LlmInvalid(Exception):
    """Model output parsed but is unusable (schema/time/consistency). Triggers the rules fallback."""

    def __init__(self, code: str, detail: str = ""):
        super().__init__(f"{code}: {detail}" if detail else code)
        self.code = code


def _clarify(intent: str, reasons: list[str], unsupported: list[str] | None = None) -> ParseResult:
    codes = list(dict.fromkeys(reasons)) or [DEFAULT_REASON[intent]]
    if unsupported:
        codes = [c for c in codes if c != "GW_UNSUPPORTED_CONDITION"]
        head = "暂不支持条件：" + "、".join(unsupported) + "。请去掉这些条件后重新发起。"
        question = " ".join([head] + [QUESTIONS[c] for c in codes[:2] if c != "GW_OVERREACH"])
    else:
        question = " ".join(QUESTIONS[c] for c in codes[:3])
    return ParseResult(intent=intent, clarify=Clarify(intent=intent, question=question),
                       unsupportedTools=list(unsupported or []))


def _parse_time(value: str, field_name: str) -> int:
    try:
        dt = datetime.fromisoformat(value)
    except ValueError as e:
        raise LlmInvalid("INVALID_TIME", f"{field_name} not ISO-8601") from e
    if dt.tzinfo is None:
        raise LlmInvalid("INVALID_TIME", f"{field_name} has no UTC offset")
    return int(dt.timestamp() * 1000)


def _norm(s: str) -> str:
    return "".join(s.lower().split())


def to_parse_result(out: LlmOutput, req: ParseRequest, ctx: dict) -> ParseResult:
    intent = out.intent
    if intent == "GIVEAWAY" and not req.actorIsAuthor:
        return _clarify("GIVEAWAY", ["GW_NOT_AUTHOR"])
    if intent == "GIVEAWAY" and out.unsupportedConditions:
        return _clarify("GIVEAWAY", out.reasons or ["GW_UNSUPPORTED_CONDITION"], list(dict.fromkeys(out.unsupportedConditions)))
    # Requirements the output leaves out: never partial execution. Overriding rules / overreach -> reject;
    # contradictions, changes of mind, unclear or unsupported asks -> clarify (an EXECUTE is downgraded).
    kinds = {d.kind for d in out.droppedRequirements}
    if kinds & DROPPED_REJECT_KINDS:
        return _clarify(intent, ["DROPPED_REJECT"])
    if kinds and out.decision == "EXECUTE":
        return _clarify(intent, ["DROPPED_CLARIFY"])
    if out.decision != "EXECUTE" or intent == "UNKNOWN":
        return _clarify(intent, [r for r in out.reasons] or [DEFAULT_REASON[intent]])

    if intent == "SEARCH":
        s = out.search
        if s is None:
            raise LlmInvalid("MISSING_COMMAND", "search")
        if s.licenseDownload is False or s.licenseCommercial is False:
            return _clarify("SEARCH", ["SEARCH_UNSUPPORTED_FILTER"])
        text = _norm(req.body or "")
        keywords = [k.strip() for k in s.keywords if k.strip()]
        keywords = list(dict.fromkeys(k for k in keywords if _norm(k) in text))
        tag = s.tag.strip().lstrip("#") if s.tag and s.tag.strip() else None
        if tag is not None and _norm(tag) not in text:
            tag = None
        if not (keywords or tag or s.aspectRatio or s.licenseDownload or s.licenseCommercial):
            return _clarify("SEARCH", ["SEARCH_NO_CRITERIA"])
        return ParseResult(intent="SEARCH", search=SearchCommand(
            query=" ".join(keywords) or None, tag=tag, aspectRatio=s.aspectRatio,
            licenseDownload=s.licenseDownload, licenseCommercial=s.licenseCommercial))

    if intent == "SUBSCRIBE":
        if req.parentCommentId is None:
            return _clarify("SUBSCRIBE", ["SUB_NO_PARENT"])
        kind = (req.context or {}).get("parentKind")
        if kind is not None and kind != "feedback":
            return _clarify("SUBSCRIBE", ["SUB_PARENT_NOT_FEEDBACK"])
        sub = out.subscribe
        if sub is None:
            raise LlmInvalid("MISSING_COMMAND", "subscribe")
        return ParseResult(intent="SUBSCRIBE", subscribe=SubscribeCommand(
            feedbackCommentId=req.parentCommentId, action=sub.action, target=sub.target))

    # GIVEAWAY, author, EXECUTE
    g = out.giveaway
    if g is None:
        raise LlmInvalid("MISSING_COMMAND", "giveaway")
    conds = list(dict.fromkeys(g.conditions))
    if not conds:
        return _clarify("GIVEAWAY", ["GW_MISSING_CONDITION"])
    disabled = [c for c in conds if c not in ctx["enabledTools"]]
    if disabled:
        return _clarify("GIVEAWAY", ["GW_UNSUPPORTED_CONDITION"], ["其他不支持的条件"])
    op = "ALL" if len(conds) == 1 else g.op
    k = None
    if op == "AT_LEAST":
        if g.k is None or g.k <= 0 or g.k > len(conds):
            return _clarify("GIVEAWAY", ["GW_INVALID_K"])
        k = g.k
    if g.winnerCount is None:
        return _clarify("GIVEAWAY", ["GW_MISSING_WINNERS"])
    if not 1 <= g.winnerCount <= 100:
        return _clarify("GIVEAWAY", ["GW_INVALID_WINNERS"])
    if g.rewardLicense is None:
        return _clarify("GIVEAWAY", ["GW_MISSING_PRIZE"])
    if g.rewardLicense == "COMMERCIAL" and ctx["postLicenseCommercial"] is False:
        return _clarify("GIVEAWAY", ["GW_LICENSE_NOT_GRANTABLE"])
    if not g.deadline:
        return _clarify("GIVEAWAY", ["GW_MISSING_DEADLINE"])
    deadline = _parse_time(g.deadline, "deadline")
    if deadline <= ctx["_nowMs"]:
        return _clarify("GIVEAWAY", ["GW_PAST_DEADLINE"])
    window = _parse_time(g.windowStart, "windowStart") if g.windowStart else None
    if window is not None and window >= deadline:
        return _clarify("GIVEAWAY", ["GW_WINDOW_AFTER_DEADLINE"])
    tree = {"op": op, **({"k": k} if k is not None else {}), "children": [{"type": c} for c in conds]}
    tz = ZoneInfo(ctx["timezone"])
    local = datetime.fromtimestamp(deadline / 1000, tz).strftime("%Y-%m-%d %H:%M")
    summary = (f"截止 {local}（{ctx['timezone']}）；资格在封池时核验；奖励最新 READY 默认版；"
               f"人数 {g.winnerCount}；许可 {g.rewardLicense}")
    return ParseResult(intent="GIVEAWAY", giveaway=GiveawayCommand(
        ruleTreeJson=json.dumps(tree, separators=(",", ":")), timezone=ctx["timezone"], windowStartMs=window,
        deadlineMs=deadline, winnerCount=g.winnerCount, rewardVersionHint="DEFAULT", rewardLicense=g.rewardLicense,
        summary=summary))


# ---------------------------------------------------------------------------------------------------------------------
# Calling the model


@dataclass
class CallRecord:
    """Per-call telemetry. Never holds the prompt or the comment text."""
    source: str
    fallbackReason: str | None = None
    latencyMs: float | None = None
    inputTokens: int | None = None
    outputTokens: int | None = None
    cacheReadTokens: int | None = None
    cacheWriteTokens: int | None = None
    stopReason: str | None = None
    requestId: str | None = None
    model: str | None = None
    extra: dict = field(default_factory=dict)


class LlmFailure(Exception):
    def __init__(self, code: str, record: CallRecord):
        super().__init__(code)
        self.code = code
        self.record = record


@dataclass
class RawOutput:
    text: str | None
    stop: str | None  # normalized: end | max_tokens | refusal | other:<x>
    request_id: str | None
    input_tokens: int | None = None
    output_tokens: int | None = None
    cache_read_tokens: int | None = None
    cache_write_tokens: int | None = None


class AnthropicBackend:
    errors = anthropic

    def __init__(self, settings: LlmSettings, client: Any):
        self.s, self.client = settings, client

    @staticmethod
    def make_client(settings: LlmSettings, key: str) -> Any:
        kw = {"base_url": settings.base_url} if settings.base_url else {}
        return anthropic.Anthropic(api_key=key, timeout=settings.timeout_s, max_retries=settings.max_retries, **kw)

    def request(self, user: str) -> dict:
        p: dict[str, Any] = {
            "model": self.s.model,
            "max_tokens": self.s.max_tokens,
            "system": [{"type": "text", "text": SYSTEM_TEXT, "cache_control": {"type": "ephemeral"}}],
            "messages": [{"role": "user", "content": user}],
            # no temperature: claude-haiku-5-5 rejects non-default sampling parameters
            "output_config": {"effort": self.s.effort, "format": {"type": "json_schema", "schema": OUTPUT_SCHEMA}},
        }
        if self.s.thinking == "disabled":
            p["thinking"] = {"type": "disabled"}
        return p

    def call(self, user: str) -> RawOutput:
        raw = self.client.messages.with_raw_response.create(**self.request(user))
        msg = raw.parse()
        u = getattr(msg, "usage", None)
        stop = {"end_turn": "end", "max_tokens": "max_tokens", "refusal": "refusal"}.get(msg.stop_reason, f"other:{msg.stop_reason}")
        return RawOutput(
            text=next((b.text for b in msg.content if getattr(b, "type", None) == "text"), None), stop=stop,
            request_id=raw.request_id,
            input_tokens=getattr(u, "input_tokens", None), output_tokens=getattr(u, "output_tokens", None),
            cache_read_tokens=getattr(u, "cache_read_input_tokens", None),
            cache_write_tokens=getattr(u, "cache_creation_input_tokens", None))


class OpenAICompatibleBackend:
    """Chat Completions on an OpenAI-compatible endpoint (DeepSeek by default).

    DeepSeek specifics (https://api-docs.deepseek.com/guides/json_mode, /api/create-chat-completion, read 2026-11-21):
    JSON mode needs the word "json" and an example in the prompt (both are in SYSTEM_TEXT), and the content may come
    back blank when the model's output was not JSON (treated as EMPTY_OUTPUT -> fallback). Thinking is on by
    default and is switched off with thinking={"type": "disabled"}; temperature only has an effect in non-thinking mode.
    """
    errors = openai

    def __init__(self, settings: LlmSettings, client: Any):
        self.s, self.client = settings, client

    @staticmethod
    def make_client(settings: LlmSettings, key: str) -> Any:
        return openai.OpenAI(api_key=key, base_url=settings.base_url, timeout=settings.timeout_s,
                             max_retries=settings.max_retries)

    def request(self, user: str) -> dict:
        p: dict[str, Any] = {
            "model": self.s.model,
            "max_tokens": self.s.max_tokens,
            "messages": [{"role": "system", "content": SYSTEM_TEXT}, {"role": "user", "content": user}],
            "response_format": {"type": "json_object"},
            "extra_body": {"thinking": {"type": "disabled" if self.s.thinking == "disabled" else "enabled"}},
        }
        if self.s.thinking == "disabled":
            p["temperature"] = 0
        return p

    def call(self, user: str) -> RawOutput:
        raw = self.client.chat.completions.with_raw_response.create(**self.request(user))
        resp = raw.parse()
        choice = resp.choices[0] if resp.choices else None
        fr = getattr(choice, "finish_reason", None)
        stop = {"stop": "end", "length": "max_tokens", "content_filter": "refusal"}.get(fr, f"other:{fr}")
        u = getattr(resp, "usage", None)
        extra = (getattr(u, "model_extra", None) or {}) if u is not None else {}
        hit = extra.get("prompt_cache_hit_tokens")
        if hit is None and u is not None and getattr(u, "prompt_tokens_details", None) is not None:
            hit = getattr(u.prompt_tokens_details, "cached_tokens", None)
        return RawOutput(
            text=getattr(choice.message, "content", None) if choice else None, stop=stop, request_id=raw.request_id,
            input_tokens=getattr(u, "prompt_tokens", None), output_tokens=getattr(u, "completion_tokens", None),
            cache_read_tokens=hit, cache_write_tokens=None)


BACKENDS = {"anthropic": AnthropicBackend, "openai_compatible": OpenAICompatibleBackend}


class LlmParser:
    """Provider-agnostic: builds the input, calls the backend, validates, converts. `client` is injectable for tests."""

    def __init__(self, settings: LlmSettings, client: Any):
        self.settings = settings
        self.backend = BACKENDS[settings.provider](settings, client)

    @classmethod
    def from_env(cls, settings: LlmSettings) -> "LlmParser":
        key = os.environ.get(API_KEY_ENV, "").strip()
        if not key:
            raise RuntimeError(f"{API_KEY_ENV} is not set")
        return cls(settings, BACKENDS[settings.provider].make_client(settings, key))

    def request_params(self, req: ParseRequest) -> dict:
        ctx = request_context(req)
        return self.backend.request(render_input({k: v for k, v in ctx.items() if not k.startswith("_")}, req.body or ""))

    def parse(self, req: ParseRequest) -> tuple[ParseResult, CallRecord]:
        ctx = request_context(req)
        user = render_input({k: v for k, v in ctx.items() if not k.startswith("_")}, req.body or "")
        rec = CallRecord(source="llm", model=self.settings.model, extra={"provider": self.settings.provider,
                                                                         "startedAtUtc": datetime.now(timezone.utc).isoformat()})
        err = self.backend.errors
        t0 = time.perf_counter()
        try:
            out_raw = self.backend.call(user)
        except err.APITimeoutError as e:
            rec.latencyMs = (time.perf_counter() - t0) * 1000
            raise LlmFailure("TIMEOUT", rec) from e
        except err.APIConnectionError as e:
            rec.latencyMs = (time.perf_counter() - t0) * 1000
            raise LlmFailure("CONNECTION_ERROR", rec) from e
        except err.RateLimitError as e:
            rec.latencyMs = (time.perf_counter() - t0) * 1000
            raise LlmFailure("RATE_LIMITED", rec) from e
        except err.APIStatusError as e:
            rec.latencyMs = (time.perf_counter() - t0) * 1000
            raise LlmFailure(f"HTTP_{e.status_code}", rec) from e
        except (err.APIError, ValueError, TypeError, AttributeError, IndexError) as e:  # malformed response body
            rec.latencyMs = (time.perf_counter() - t0) * 1000
            raise LlmFailure("BAD_RESPONSE", rec) from e
        rec.latencyMs = (time.perf_counter() - t0) * 1000
        rec.requestId = out_raw.request_id
        rec.inputTokens, rec.outputTokens = out_raw.input_tokens, out_raw.output_tokens
        rec.cacheReadTokens, rec.cacheWriteTokens = out_raw.cache_read_tokens, out_raw.cache_write_tokens
        rec.stopReason = out_raw.stop
        if out_raw.stop == "refusal":
            raise LlmFailure("REFUSAL", rec)
        if out_raw.stop == "max_tokens":
            raise LlmFailure("MAX_TOKENS", rec)
        if out_raw.text is None or not out_raw.text.strip():
            raise LlmFailure("EMPTY_OUTPUT", rec)
        try:
            out = LlmOutput.model_validate_json(out_raw.text)
        except ValidationError as e:
            raise LlmFailure("SCHEMA_INVALID", rec) from e
        try:
            result = to_parse_result(out, req, ctx)
        except LlmInvalid as e:
            raise LlmFailure(e.code, rec) from e
        result.source = "llm"
        return result, rec
