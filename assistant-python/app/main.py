"""Material community assistant — FastAPI intent parser (no DB access)."""
from __future__ import annotations

import os
import re
from typing import Any, Literal

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="content-assistant", version="0.1.0")
INTERNAL = os.environ.get("CONTENT_ASSISTANT_INTERNAL_TOKEN", "")


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


def _auth(token: str | None) -> None:
    if INTERNAL and token != INTERNAL:
        raise HTTPException(status_code=401, detail="UNAUTHORIZED")


def _contains(text: str, *keys: str) -> bool:
    return any(k in text for k in keys)


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/parse", response_model=ParseResult)
def parse(req: ParseRequest, x_content_internal_token: str | None = Header(default=None)) -> ParseResult:
    _auth(x_content_internal_token)
    raw = req.body or ""
    text = re.sub(r"@助手|@assistant", " ", raw, flags=re.I).strip()
    if not text:
        return ParseResult(intent="UNKNOWN", clarify=Clarify(intent="UNKNOWN", question="请说明要找素材、蹲改进还是抽奖。"))

    if _contains(text, "蹲", "订阅", "改进", "修改结果"):
        if req.parentCommentId is None:
            return ParseResult(
                intent="SUBSCRIBE",
                clarify=Clarify(intent="SUBSCRIBE", question="请在目标反馈评论下回复「@助手 蹲这条的修改结果」。"),
            )
        return ParseResult(intent="SUBSCRIBE", subscribe=SubscribeCommand(feedbackCommentId=req.parentCommentId))

    if _contains(text, "抽", "开奖", "抽取", "抽奖"):
        if not req.actorIsAuthor:
            return ParseResult(intent="GIVEAWAY", clarify=Clarify(intent="GIVEAWAY", question="只有作者可以发起抽奖。"))
        like = _contains(text, "点赞")
        comment = _contains(text, "评论")
        favorite = _contains(text, "收藏")
        follow = _contains(text, "关注")
        purchase = _contains(text, "购买", "买过", "下单")
        if not (like or comment or favorite or follow or purchase):
            return ParseResult(
                intent="GIVEAWAY",
                clarify=Clarify(intent="GIVEAWAY", question="请说明参与条件（点赞/评论/收藏/关注/购买）以及截止时间和中奖人数。"),
            )
        mode = "ALL"
        k = 0
        if _contains(text, "任一", "或者", "或"):
            mode = "ANY"
        m = re.search(r"至少\s*(\d+)|任选\s*(\d+)", text)
        if m:
            mode = "AT_LEAST"
            k = int(m.group(1) or m.group(2))
        if "选两" in text and mode != "AT_LEAST" and "指定" not in text:
            return ParseResult(
                intent="GIVEAWAY",
                clarify=Clarify(intent="GIVEAWAY", question="「选两项」是指定两项全部满足，还是参与者任意满足两项？"),
            )
        deadline = None
        em = re.search(r"截止epoch:(\d+)", text)
        if em:
            deadline = int(em.group(1))
        else:
            sm = re.search(r"(\d+)\s*秒后", text)
            if sm:
                import time

                deadline = int(time.time() * 1000) + int(sm.group(1)) * 1000
        if deadline is None:
            return ParseResult(intent="GIVEAWAY", clarify=Clarify(intent="GIVEAWAY", question="请给出明确截止时间。"))
        winners = 1
        wm = re.search(r"抽\s*(\d+)\s*人|(\d+)\s*人", text)
        if wm:
            winners = int(wm.group(1) or wm.group(2))
        license_ = "COMMERCIAL" if _contains(text, "商用") else "DOWNLOAD"
        children = []
        if like:
            children.append('{"type":"LIKE"}')
        if comment:
            children.append('{"type":"COMMENT"}')
        if favorite:
            children.append('{"type":"FAVORITE"}')
        if follow:
            children.append('{"type":"FOLLOW"}')
        if purchase:
            children.append('{"type":"PURCHASE"}')
        tree = f'{{"op":"{mode}"' + (f',"k":{k}' if mode == "AT_LEAST" else "") + ',"children":[' + ",".join(children) + "]}"
        summary = f"截止 {deadline}；资格在封池时核验；奖励最新 READY 默认版；人数 {winners}；许可 {license_}"
        return ParseResult(
            intent="GIVEAWAY",
            giveaway=GiveawayCommand(
                ruleTreeJson=tree,
                deadlineMs=deadline,
                winnerCount=winners,
                rewardLicense=license_,
                summary=summary,
            ),
        )

    aspect = None
    if _contains(text, "竖屏", "竖版"):
        aspect = "PORTRAIT"
    elif _contains(text, "横屏", "横版"):
        aspect = "LANDSCAPE"
    elif _contains(text, "方形", "正方形"):
        aspect = "SQUARE"
    download = True if _contains(text, "可下载", "允许下载") else None
    commercial = True if _contains(text, "商用", "允许商用") else None
    tag = None
    tm = re.search(r"标签[:：]?\s*(\S+)|#(\S+)", text)
    if tm:
        tag = tm.group(1) or tm.group(2)
    q = re.sub(r"找|搜索|素材|竖屏|横屏|方形|允许商用|可下载|商用|下载", " ", text)
    q = re.sub(r"[、，。！？]+", " ", q)
    q = re.sub(r"的", " ", q)
    q = re.sub(r"\s+", " ", q).strip() or None
    if not any([q, tag, aspect, download, commercial]):
        return ParseResult(intent="SEARCH", clarify=Clarify(intent="SEARCH", question="请补充关键词、标签或画幅/许可条件。"))
    return ParseResult(
        intent="SEARCH",
        search=SearchCommand(query=q, tag=tag, aspectRatio=aspect, licenseDownload=download, licenseCommercial=commercial),
    )
