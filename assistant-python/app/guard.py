"""Override-phrase backstop.

A comment that asks to ignore/disregard the assistant's rules or instructions never executes, whatever produced the
command (the LLM or the rules fallback). It can only turn execution into non-execution, never the reverse.
Deliberately narrow (the family named in the requirement: 忽略/无视 ... 规则/指令, and the English equivalent);
the model's droppedRequirements field is the primary defence, this is the extra fuse.
"""
from __future__ import annotations

import re

_ZH = re.compile(r"(忽略|无视)[^。！？!?\n]{0,12}?(规则|指令|提示词|设定)")
_EN = re.compile(r"\b(ignore|disregard)\b(?:\W+\w+){0,4}?\W+(instructions?|rules?|prompts?)\b", re.IGNORECASE)


def override_phrase(text: str | None) -> bool:
    t = text or ""
    return bool(_ZH.search(t) or _EN.search(t))
