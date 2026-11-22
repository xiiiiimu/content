"""Deterministic keyword/regex parser (the pre-LLM implementation, logic unchanged). Used when
CONTENT_ASSISTANT_PARSER_MODE=rules and as the fallback of llm_with_fallback."""
from __future__ import annotations

import re
import time

from app.contract import Clarify, GiveawayCommand, ParseRequest, ParseResult, SearchCommand, SubscribeCommand


def _now_ms() -> int:
    return int(time.time() * 1000)


def _contains(text: str, *keys: str) -> bool:
    return any(k in text for k in keys)


# A11: conditions the platform cannot evaluate or grant. Each hit is reported in unsupportedTools (label, patterns);
# Java refuses the whole command when the list is non-empty, so nothing is silently dropped.
UNSUPPORTED_GIVEAWAY: list[tuple[str, str]] = [
    ("转发", r"转发"),
    ("分享", r"分享"),
    ("评论字数", r"字数|\d+\s*字"),
    ("关注时长", r"关注满|关注超过|老粉|粉龄|关注时长"),
    ("@好友", r"@\S*好友|艾特|圈\S*好友"),
    ("打赏", r"打赏"),
    ("等级", r"等级|级以上"),
    ("弹幕", r"弹幕"),
    ("下载记录", r"下载过|下载记录"),
    ("未关注（否定条件）", r"没关注|未关注|不关注|没有关注"),
    ("转售授权", r"转售|转卖|二次销售"),
    ("其他帖子的奖品", r"另一个帖子|其他帖子|别的帖子|另一个作品"),
    ("作者本人参与", r"作者(本人)?也参与|我也参与|作者本人参与|我自己也参与"),
    ("同一人多次参与", r"多次(评论|参与|抽)|增加中奖率|多评多中"),
]
CANCEL_WORDS = ("取消", "不用蹲", "不想蹲", "别蹲", "不要蹲", "别通知", "不要通知", "不用通知", "退订")
ALL_WORDS = ("所有", "全部", "大家", "每个人", "每一个")


def _unsupported_giveaway(text: str) -> list[str]:
    return [label for label, pat in UNSUPPORTED_GIVEAWAY if re.search(pat, text)]


def _subscribe_target(text: str) -> str:
    if _contains(text, *ALL_WORDS):
        return "ALL"
    if re.search(r"帮\s*@|帮(他|她|别人|朋友)|替\s*@|替(他|她|别人)|给\s*@", text):
        return "OTHERS"
    return "SELF"


def parse_rules(req: ParseRequest) -> ParseResult:
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
        kind = req.context.get("parentKind")
        if kind is not None and kind != "feedback":
            return ParseResult(
                intent="SUBSCRIBE",
                clarify=Clarify(intent="SUBSCRIBE", question="只能订阅带素材版本的反馈评论，请在目标反馈下回复「@助手 蹲这条的修改结果」。"),
            )
        action = "CANCEL" if _contains(text, *CANCEL_WORDS) else "SUBSCRIBE"
        return ParseResult(
            intent="SUBSCRIBE",
            subscribe=SubscribeCommand(feedbackCommentId=req.parentCommentId, action=action, target=_subscribe_target(text)),
        )

    if _contains(text, "抽", "开奖", "抽取", "抽奖"):
        if not req.actorIsAuthor:
            return ParseResult(intent="GIVEAWAY", clarify=Clarify(intent="GIVEAWAY", question="只有作者可以发起抽奖。"))
        unsupported = _unsupported_giveaway(text)
        if unsupported:
            return ParseResult(
                intent="GIVEAWAY",
                clarify=Clarify(intent="GIVEAWAY", question="暂不支持条件：" + "、".join(unsupported) + "。请去掉这些条件后重新发起。"),
                unsupportedTools=unsupported,
            )
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
        m = re.search(r"至少(?:满足)?\s*(\d+)|任选\s*(\d+)", text)
        if m:
            mode = "AT_LEAST"
            k = int(m.group(1) or m.group(2))
            n = sum([like, comment, favorite, follow, purchase])
            if k <= 0 or k > n:
                return ParseResult(
                    intent="GIVEAWAY",
                    clarify=Clarify(intent="GIVEAWAY", question=f"「至少满足{k}项」但只列出了 {n} 项条件，请确认。"),
                )
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
                deadline = _now_ms() + int(sm.group(1)) * 1000
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
