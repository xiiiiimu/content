"""/v1/parse request/response contract shared by the rules and LLM parsers. Java (HttpAssistantParser) reads these
fields; `source` and `fallbackReason` are additive (Java ignores unknown fields)."""
from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field


class ParseRequest(BaseModel):
    taskId: int
    postId: int
    actorUserId: int
    parentCommentId: int | None = None
    body: str
    actorIsAuthor: bool = False
    context: dict[str, Any] = Field(default_factory=dict)


class Clarify(BaseModel):
    intent: str
    question: str


class SearchCommand(BaseModel):
    query: str | None = None
    tag: str | None = None
    aspectRatio: str | None = None
    licenseDownload: bool | None = None
    licenseCommercial: bool | None = None


class SubscribeCommand(BaseModel):
    feedbackCommentId: int
    # Parser-only semantics (A11): CANCEL for negation/cancel, OTHERS/ALL when the request is not for the actor alone.
    action: Literal["SUBSCRIBE", "CANCEL"] = "SUBSCRIBE"
    target: Literal["SELF", "OTHERS", "ALL"] = "SELF"


class GiveawayCommand(BaseModel):
    ruleTreeJson: str
    timezone: str = "Asia/Shanghai"
    windowStartMs: int | None = None
    deadlineMs: int | None = None
    winnerCount: int = 1
    rewardVersionHint: str | None = "DEFAULT"
    rewardLicense: str = "DOWNLOAD"
    summary: str | None = None


class ParseResult(BaseModel):
    intent: Literal["SEARCH", "SUBSCRIBE", "GIVEAWAY", "UNKNOWN"]
    clarify: Clarify | None = None
    search: SearchCommand | None = None
    subscribe: SubscribeCommand | None = None
    giveaway: GiveawayCommand | None = None
    unsupportedTools: list[str] = Field(default_factory=list)
    # Which parser produced this result: rules (mode=rules), llm, or rules_fallback (LLM failed; reason in fallbackReason).
    source: Literal["rules", "llm", "rules_fallback"] | None = None
    fallbackReason: str | None = None
