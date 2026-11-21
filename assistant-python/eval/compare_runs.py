"""Aggregate repeated eval runs (and an optional rules baseline) into mean / spread and per-row stability.

  .venv/Scripts/python.exe eval/compare_runs.py --out <dir> --baseline <rules-run-dir> <llm-run-1> <llm-run-2> <llm-run-3>

Each run dir is a run_eval.py output (metrics.json, predictions.jsonl). Writes compare.md / compare.json.
Stability: share of rows whose (intent, executed?, command fields) are identical in every LLM run.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path


def load(d: Path) -> tuple[dict, list[dict]]:
    m = json.loads((d / "metrics.json").read_text(encoding="utf-8"))
    p = [json.loads(x) for x in (d / "predictions.jsonl").read_text(encoding="utf-8").splitlines() if x.strip()]
    return m, p


def get(m: dict, path: str):
    cur = m
    for part in path.split("/"):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur


METRICS = [
    ("意图准确率", "intent_accuracy", "pct"),
    ("意图宏 F1", "intent_macro_f1", "f3"),
    ("F1 SEARCH", "intent_per_class/SEARCH/f1", "f3"),
    ("F1 SUBSCRIBE", "intent_per_class/SUBSCRIBE/f1", "f3"),
    ("F1 GIVEAWAY", "intent_per_class/GIVEAWAY/f1", "f3"),
    ("F1 UNKNOWN", "intent_per_class/UNKNOWN/f1", "f3"),
    ("执行判定准确率", "action_accuracy", "pct"),
    ("补问 precision", "clarify/precision", "pct"),
    ("补问 recall", "clarify/recall", "pct"),
    ("REJECT 行未执行（比例）", "_reject_ratio", "pct"),
    ("不支持条件标出（比例）", "_unsupported_ratio", "pct"),
    ("解析层不该执行却产出命令（条）", "executed_when_gold_not_execute", "int"),
    ("SEARCH.queryTerms acc", "field_accuracy/SEARCH.queryTerms/acc", "pct"),
    ("SEARCH.queryClean acc", "field_accuracy/SEARCH.queryClean/acc", "pct"),
    ("SEARCH.aspectRatio acc", "field_accuracy/SEARCH.aspectRatio/acc", "pct"),
    ("SEARCH 全字段 acc", "field_accuracy/SEARCH.ALL/acc", "pct"),
    ("SUBSCRIBE.feedbackCommentId acc", "field_accuracy/SUBSCRIBE.feedbackCommentId/acc", "pct"),
    ("GIVEAWAY.conditions acc", "field_accuracy/GIVEAWAY.conditions/acc", "pct"),
    ("GIVEAWAY.op acc", "field_accuracy/GIVEAWAY.op/acc", "pct"),
    ("GIVEAWAY.k acc", "field_accuracy/GIVEAWAY.k/acc", "pct"),
    ("GIVEAWAY.deadlineMs acc", "field_accuracy/GIVEAWAY.deadlineMs/acc", "pct"),
    ("GIVEAWAY.winnerCount acc", "field_accuracy/GIVEAWAY.winnerCount/acc", "pct"),
    ("GIVEAWAY.rewardLicense acc", "field_accuracy/GIVEAWAY.rewardLicense/acc", "pct"),
    ("GIVEAWAY.rewardVersionHint acc", "field_accuracy/GIVEAWAY.rewardVersionHint/acc", "pct"),
    ("GIVEAWAY 全字段 acc", "field_accuracy/GIVEAWAY.ALL/acc", "pct"),
    ("GIVEAWAY 全字段 acc_cond", "field_accuracy/GIVEAWAY.ALL/acc_cond", "pct"),
    ("[去掉少样本行] n", "excluding_few_shot/n", "int"),
    ("[去掉少样本行] 意图准确率", "excluding_few_shot/intent_accuracy", "pct"),
    ("[去掉少样本行] 意图宏 F1", "excluding_few_shot/intent_macro_f1", "f3"),
    ("[去掉少样本行] 执行判定准确率", "excluding_few_shot/action_accuracy", "pct"),
    ("[去掉少样本行] 补问 precision", "excluding_few_shot/clarify/precision", "pct"),
    ("[去掉少样本行] 补问 recall", "excluding_few_shot/clarify/recall", "pct"),
    ("[去掉少样本行] GIVEAWAY 全字段 acc", "excluding_few_shot/field_accuracy/GIVEAWAY.ALL/acc", "pct"),
    ("[去掉少样本行] SEARCH.queryClean acc", "excluding_few_shot/field_accuracy/SEARCH.queryClean/acc", "pct"),
    ("降级比例", "llm/fallback_ratio", "pct"),
    ("缓存命中比例", "llm/cache_hit_ratio", "pct"),
    ("按高峰价计费的调用数", "llm/peak_priced_calls", "int"),
    ("延迟 P50 (ms)", "llm/latency_ms/p50", "int"),
    ("延迟 P95 (ms)", "llm/latency_ms/p95", "int"),
    ("输入 token/次", "llm/tokens_per_call/inputTokens", "int"),
    ("缓存读 token/次", "llm/tokens_per_call/cacheReadTokens", "int"),
    ("缓存写 token/次", "llm/tokens_per_call/cacheWriteTokens", "int"),
    ("输出 token/次", "llm/tokens_per_call/outputTokens", "int"),
    ("成本 USD/次", "llm/cost_usd_per_call", "usd"),
    ("成本 USD/整轮", "llm/cost_usd", "usd"),
]


def derived(m: dict) -> dict:
    m = dict(m)
    rr, ur = m.get("reject_rows") or {}, m.get("unsupported_rows") or {}
    m["_reject_ratio"] = rr["not_executed"] / rr["n"] if rr.get("n") else None
    m["_unsupported_ratio"] = ur["flagged"] / ur["n"] if ur.get("n") else None
    return m


def fmt(v, kind: str) -> str:
    if v is None:
        return "n/a"
    if kind == "pct":
        return f"{v * 100:.1f}%"
    if kind == "f3":
        return f"{v:.3f}"
    if kind == "usd":
        return f"${v:.5f}"
    return f"{v:.0f}"


def spread(vals: list, kind: str) -> str:
    xs = [v for v in vals if v is not None]
    if not xs:
        return "n/a"
    mean = statistics.fmean(xs)
    sd = statistics.stdev(xs) if len(xs) > 1 else 0.0
    if kind == "pct":
        return f"{mean * 100:.1f}% ± {sd * 100:.1f}（{min(xs) * 100:.1f}–{max(xs) * 100:.1f}）"
    if kind == "f3":
        return f"{mean:.3f} ± {sd:.3f}（{min(xs):.3f}–{max(xs):.3f}）"
    if kind == "usd":
        return f"${mean:.5f} ± {sd:.5f}"
    return f"{mean:.1f} ± {sd:.1f}（{min(xs):.0f}–{max(xs):.0f}）"


def signature(r: dict) -> str:
    p = r["pred"]
    cmd = p.get("search") or p.get("subscribe") or p.get("giveaway")
    if isinstance(cmd, dict) and "summary" in cmd:
        cmd = {k: v for k, v in cmd.items() if k != "summary"}
    return json.dumps([p["intent"], r["predAction"], cmd, sorted(p.get("unsupportedTools") or [])], ensure_ascii=False, sort_keys=True)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--baseline", help="rules run dir")
    ap.add_argument("runs", nargs="+")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=False)
    runs = [load(Path(d)) for d in a.runs]
    base = derived(load(Path(a.baseline))[0]) if a.baseline else None
    ms = [derived(m) for m, _ in runs]

    lines = [f"# 规则版 vs 大模型版（{len(runs)} 次重复）", "", f"- runs: {', '.join(a.runs)}",
             f"- baseline: {a.baseline or '无'}", "",
             "| 指标 | 规则版 | 大模型版 均值 ± 标准差（最小–最大） | " + " | ".join(f"run{i + 1}" for i in range(len(runs))) + " |",
             "|---|---|---|" + "---|" * len(runs)]
    table = {}
    for label, path, kind in METRICS:
        vals = [get(m, path) for m in ms]
        b = get(base, path) if base else None
        table[path] = {"baseline": b, "runs": vals}
        lines.append(f"| {label} | {fmt(b, kind)} | {spread(vals, kind)} | " + " | ".join(fmt(v, kind) for v in vals) + " |")

    # per-row stability across LLM runs
    preds = [p for _, p in runs]
    ids = [r["id"] for r in preds[0]]
    unstable = []
    for i, rid in enumerate(ids):
        sigs = {signature(p[i]) for p in preds}
        acts = {(p[i]["pred"]["intent"], p[i]["predAction"]) for p in preds}
        if len(sigs) > 1:
            unstable.append({"id": rid, "text": preds[0][i]["text"], "gold": f"{preds[0][i]['gold']['intent']}/{preds[0][i]['gold']['action']}",
                             "variants": len(sigs), "decisionVaries": len(acts) > 1,
                             "outcomes": [f"{p[i]['pred']['intent']}/{p[i]['predAction']}" + (f"/{p[i]['pred'].get('fallbackReason')}" if p[i]['pred'].get('fallbackReason') else "") for p in preds]})
    n = len(ids)
    stab = {"rows": n, "identical_all_runs": n - len(unstable), "identical_ratio": (n - len(unstable)) / n if n else None,
            "decision_varies": sum(u["decisionVaries"] for u in unstable)}
    lines += ["", "## 输出稳定性", "",
              f"- 各次完全一致（意图、是否执行、命令字段、unsupportedTools）：{stab['identical_all_runs']}/{n}（{fmt(stab['identical_ratio'], 'pct')}）",
              f"- 意图或执行判定在各次之间变化：{stab['decision_varies']} 条", ""]
    if unstable:
        lines += ["| id | text | gold | 各次结果 | 判定变化 |", "|---|---|---|---|---|"]
        for u in unstable:
            lines.append(f"| {u['id']} | {u['text'].replace('|', '/')} | {u['gold']} | {' ; '.join(u['outcomes'])} | {'是' if u['decisionVaries'] else '否（仅字段）'} |")
    (out / "compare.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (out / "compare.json").write_text(json.dumps({"table": table, "stability": stab, "unstable": unstable}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
