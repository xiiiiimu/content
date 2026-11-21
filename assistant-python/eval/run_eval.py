"""Offline evaluation of the rule-based intent parser (app.main) against eval/dataset.jsonl.

Usage (from assistant-python/):
  .venv/Scripts/python.exe eval/run_eval.py                    # dev split (default)
  .venv/Scripts/python.exe eval/run_eval.py --split test       # held-out; never tune rules on it
  .venv/Scripts/python.exe eval/run_eval.py --split all --emit-e2e

The parser is called through the real FastAPI endpoint (TestClient), with the same request
shape Java sends today (context={}). time.time is frozen to the dataset's REF_NOW so that
relative deadlines ("N秒后") are deterministic; parser logic is not modified.

Outputs go to ../target/assistant-eval/<timestamp>-<split>/ (private, not committed):
  predictions.jsonl, errors.jsonl, metrics.json, summary.md, [e2e-cases.jsonl]
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

INTENTS = ["SEARCH", "SUBSCRIBE", "GIVEAWAY", "UNKNOWN"]
AUTHOR_ID, VIEWER_ID, PARENT_ID, POST_ID = 100, 200, 9001, 1
SEARCH_FIELDS = ["queryTerms", "queryClean", "tag", "aspectRatio", "licenseDownload", "licenseCommercial"]
GIVEAWAY_FIELDS = ["conditions", "op", "k", "deadlineMs", "winnerCount", "rewardLicense", "rewardVersionHint"]
SUBSCRIBE_FIELDS = ["feedbackCommentId"]
FIELDS = {"SEARCH": SEARCH_FIELDS, "SUBSCRIBE": SUBSCRIBE_FIELDS, "GIVEAWAY": GIVEAWAY_FIELDS}


def load(path: Path, split: str) -> list[dict]:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    return rows if split == "all" else [r for r in rows if r["split"] == split]


def build_request(i: int, row: dict) -> dict:
    ctx = row["context"]
    return {
        "taskId": i + 1,
        "postId": POST_ID,
        "actorUserId": AUTHOR_ID if ctx["actorIsAuthor"] else VIEWER_ID,
        "parentCommentId": PARENT_ID if ctx["parent"] else None,
        "body": row["text"],
        "actorIsAuthor": ctx["actorIsAuthor"],
        "context": {},  # mirrors AssistantService.tick(): Map.of()
    }


def call_parser(client: TestClient, rows: list[dict]) -> list[dict]:
    now_ms = rows[0]["context"]["nowMs"] if rows else 0
    out = []
    with mock.patch("time.time", return_value=now_ms / 1000):
        for i, row in enumerate(rows):
            r = client.post("/v1/parse", json=build_request(i, row))
            r.raise_for_status()
            out.append(r.json())
    return out


def pred_action(p: dict) -> str:
    if p.get("clarify") or p.get("unsupportedTools"):
        return "NON_EXEC"
    if p.get("search") or p.get("subscribe") or p.get("giveaway"):
        return "EXECUTE"
    return "NON_EXEC"


def tree_view(rule_json: str) -> dict:
    try:
        t = json.loads(rule_json)
    except Exception:
        return {"op": "INVALID", "k": None, "conditions": []}
    if "type" in t:
        return {"op": "SINGLE", "k": None, "conditions": [t["type"]]}
    children = t.get("children") or []
    conds = sorted(c.get("type", "NESTED") for c in children)
    op = "SINGLE" if len(children) == 1 else t.get("op")
    return {"op": op, "k": t.get("k") if op == "AT_LEAST" else None, "conditions": conds}


def field_values(intent: str, p: dict) -> dict:
    if intent == "SEARCH" and p.get("search"):
        s = p["search"]
        return {"query": s.get("query"), "tag": s.get("tag"), "aspectRatio": s.get("aspectRatio"),
                "licenseDownload": s.get("licenseDownload"), "licenseCommercial": s.get("licenseCommercial")}
    if intent == "SUBSCRIBE" and p.get("subscribe"):
        return {"feedbackCommentId": p["subscribe"].get("feedbackCommentId")}
    if intent == "GIVEAWAY" and p.get("giveaway"):
        g = p["giveaway"]
        return {**tree_view(g.get("ruleTreeJson") or ""), "deadlineMs": g.get("deadlineMs"),
                "winnerCount": g.get("winnerCount"), "rewardLicense": g.get("rewardLicense"),
                "rewardVersionHint": g.get("rewardVersionHint")}
    return {}


def compare_fields(intent: str, gold: dict, pv: dict) -> dict[str, bool]:
    res: dict[str, bool] = {}
    if intent == "SEARCH":
        q = pv.get("query")
        terms = gold["queryTerms"]
        res["queryTerms"] = all(t.lower() in (q or "").lower() for t in terms)
        if terms:
            toks = (q or "").split()
            res["queryClean"] = bool(toks) and all(t in terms for t in toks)
        else:
            res["queryClean"] = q is None
        for f in ("tag", "aspectRatio", "licenseDownload", "licenseCommercial"):
            res[f] = pv.get(f) == gold[f]
    elif intent == "SUBSCRIBE":
        res["feedbackCommentId"] = pv.get("feedbackCommentId") == PARENT_ID
    elif intent == "GIVEAWAY":
        for f in GIVEAWAY_FIELDS:
            res[f] = pv.get(f) == gold[f]
    return res


def macro_f1(pairs: list[tuple[str, str]]) -> tuple[float, dict]:
    per = {}
    for lab in INTENTS:
        tp = sum(g == lab and p == lab for g, p in pairs)
        fp = sum(g != lab and p == lab for g, p in pairs)
        fn = sum(g == lab and p != lab for g, p in pairs)
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
        per[lab] = {"precision": prec, "recall": rec, "f1": f1, "support": tp + fn}
    labs = [lab for lab in INTENTS if per[lab]["support"] > 0]
    return (sum(per[lab]["f1"] for lab in labs) / len(labs) if labs else 0.0), per


def evaluate(rows: list[dict], preds: list[dict]) -> tuple[dict, list[dict], list[dict]]:
    records, errors = [], []
    field_hits: dict[str, Counter] = defaultdict(Counter)
    for row, p in zip(rows, preds):
        g = row["expected"]
        gi, ga = g["intent"], g["action"]
        pi, pa = p["intent"], pred_action(p)
        pclar = bool(p.get("clarify") or p.get("unsupportedTools"))
        pv = field_values(gi, p) if pa == "EXECUTE" and pi == gi else {}
        fields = {}
        if ga == "EXECUTE" and gi in FIELDS:
            if pv:
                fields = compare_fields(gi, g["params"], pv)
            else:
                fields = {f: False for f in FIELDS[gi]}
            for f, ok in fields.items():
                field_hits[f"{gi}.{f}"]["n"] += 1
                field_hits[f"{gi}.{f}"]["ok"] += ok
                if pv:
                    field_hits[f"{gi}.{f}"]["n_cond"] += 1
                    field_hits[f"{gi}.{f}"]["ok_cond"] += ok
            field_hits[f"{gi}.ALL"]["n"] += 1
            field_hits[f"{gi}.ALL"]["ok"] += bool(fields) and all(fields.values())
            if pv:
                field_hits[f"{gi}.ALL"]["n_cond"] += 1
                field_hits[f"{gi}.ALL"]["ok_cond"] += all(fields.values())
        err_types = []
        if pi != gi:
            err_types.append(f"INTENT:{gi}->{pi}")
        if ga == "EXECUTE" and pa != "EXECUTE":
            err_types.append("SHOULD_EXECUTE_BUT_NOT")
        if ga != "EXECUTE" and pa == "EXECUTE":
            err_types.append(f"EXECUTED_BUT_GOLD_{ga}")
        if ga == "CLARIFY" and g["unsupported"] and not p.get("unsupportedTools"):
            err_types.append("UNSUPPORTED_NOT_FLAGGED")
        if pv:
            err_types += [f"FIELD:{f}" for f, ok in fields.items() if not ok]
        rec = {"id": row["id"], "split": row["split"], "category": row["category"], "text": row["text"],
               "context": {k: row["context"][k] for k in ("isReply", "parent", "actorIsAuthor", "postLicenseCommercial")},
               "gold": g, "pred": p, "predAction": pa, "predClarify": pclar, "fields": fields, "errors": err_types}
        records.append(rec)
        if err_types:
            errors.append(rec)

    n = len(records)
    pairs = [(r["gold"]["intent"], r["pred"]["intent"]) for r in records]
    mf1, per = macro_f1(pairs)
    # Clarify P/R: only rows where the gold behaviour is EXECUTE or CLARIFY (REJECT rows are scored separately).
    cr = [r for r in records if r["gold"]["action"] in ("EXECUTE", "CLARIFY")]
    tp = sum(r["gold"]["action"] == "CLARIFY" and r["predClarify"] for r in cr)
    fp = sum(r["gold"]["action"] == "EXECUTE" and r["predClarify"] for r in cr)
    fn = sum(r["gold"]["action"] == "CLARIFY" and not r["predClarify"] for r in cr)
    rej = [r for r in records if r["gold"]["action"] == "REJECT"]
    unsup = [r for r in records if r["gold"]["unsupported"]]
    gold_exec = [r for r in records if r["gold"]["action"] == "EXECUTE"]
    metrics = {
        "n": n,
        "intent_accuracy": sum(g == p for g, p in pairs) / n if n else 0.0,
        "intent_macro_f1": mf1,
        "intent_per_class": per,
        "intent_confusion": {f"{g}->{p}": c for (g, p), c in sorted(Counter(pairs).items())},
        "action_accuracy": sum((r["gold"]["action"] == "EXECUTE") == (r["predAction"] == "EXECUTE") for r in records) / n if n else 0.0,
        "clarify": {"n": len(cr), "tp": tp, "fp": fp, "fn": fn,
                    "precision": tp / (tp + fp) if tp + fp else None,
                    "recall": tp / (tp + fn) if tp + fn else None},
        "reject_rows": {"n": len(rej), "not_executed": sum(r["predAction"] != "EXECUTE" for r in rej)},
        "unsupported_rows": {"n": len(unsup), "flagged": sum(bool(r["pred"].get("unsupportedTools")) for r in unsup),
                             "not_executed": sum(r["predAction"] != "EXECUTE" for r in unsup)},
        "executed_when_gold_not_execute": sum(r["gold"]["action"] != "EXECUTE" and r["predAction"] == "EXECUTE" for r in records),
        "gold_execute_rows": len(gold_exec),
        "field_accuracy": {
            k: {"n": v["n"], "acc": v["ok"] / v["n"] if v["n"] else None,
                "n_cond": v["n_cond"], "acc_cond": v["ok_cond"] / v["n_cond"] if v["n_cond"] else None}
            for k, v in sorted(field_hits.items())
        },
        "error_type_counts": dict(Counter(t.split(":")[0] if t.startswith("FIELD") else t
                                          for r in errors for t in r["errors"]).most_common()),
        "error_rows": len(errors),
    }
    return metrics, records, errors


def pct(x) -> str:
    return "n/a" if x is None else f"{x * 100:.1f}%"


def summary_md(metrics: dict, errors: list[dict], split: str, limit: int) -> str:
    m = metrics
    lines = [f"# Assistant parser eval — split={split}, n={m['n']}", "",
             f"- 意图准确率: {pct(m['intent_accuracy'])}",
             f"- 意图宏 F1: {m['intent_macro_f1']:.3f}",
             f"- 执行判定准确率 (EXECUTE vs 不执行): {pct(m['action_accuracy'])}",
             f"- 补问 precision: {pct(m['clarify']['precision'])} / recall: {pct(m['clarify']['recall'])} "
             f"(tp={m['clarify']['tp']} fp={m['clarify']['fp']} fn={m['clarify']['fn']}, n={m['clarify']['n']})",
             f"- REJECT 行未执行: {m['reject_rows']['not_executed']}/{m['reject_rows']['n']}",
             f"- 不支持条件被标出 unsupportedTools: {m['unsupported_rows']['flagged']}/{m['unsupported_rows']['n']}"
             f"，未执行: {m['unsupported_rows']['not_executed']}/{m['unsupported_rows']['n']}",
             f"- 解析层“不该执行却产出命令”: {m['executed_when_gold_not_execute']}", "",
             "## 意图逐类", "", "| intent | P | R | F1 | support |", "|---|---|---|---|---|"]
    for lab, v in m["intent_per_class"].items():
        lines.append(f"| {lab} | {pct(v['precision'])} | {pct(v['recall'])} | {v['f1']:.3f} | {v['support']} |")
    lines += ["", "## 混淆 (gold->pred)", ""] + [f"- {k}: {v}" for k, v in m["intent_confusion"].items()]
    lines += ["", "## 逐字段参数准确率", "",
              "acc = 占全部 gold-EXECUTE 行（未执行/意图错算错）；acc_cond = 仅在产出同意图命令的行上", "",
              "| field | n | acc | n_cond | acc_cond |", "|---|---|---|---|---|"]
    for k, v in m["field_accuracy"].items():
        lines.append(f"| {k} | {v['n']} | {pct(v['acc'])} | {v['n_cond']} | {pct(v['acc_cond'])} |")
    lines += ["", "## 错误类型计数", ""] + [f"- {k}: {v}" for k, v in m["error_type_counts"].items()]
    lines += ["", f"## 错误样例（{min(limit, len(errors))}/{len(errors)}）", "",
              "| id | category | text | gold | pred | errors |", "|---|---|---|---|---|---|"]
    for r in errors[:limit]:
        g = r["gold"]
        pred = r["pred"]
        ptxt = pred["intent"] + ("/clarify" if r["predClarify"] else ("/exec" if r["predAction"] == "EXECUTE" else ""))
        lines.append(f"| {r['id']} | {r['category']} | {r['text'].replace('|', '/')} | {g['intent']}/{g['action']} | "
                     f"{ptxt} | {', '.join(r['errors'])} |")
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", default=str(HERE / "dataset.jsonl"))
    ap.add_argument("--split", choices=["dev", "test", "all"], default="dev")
    ap.add_argument("--out", default=None, help="output dir (default ../target/assistant-eval/<ts>-<split>)")
    ap.add_argument("--emit-e2e", action="store_true", help="also write e2e-cases.jsonl for AssistantEvalE2EIT")
    ap.add_argument("--max-errors", type=int, default=400)
    args = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    rows = load(Path(args.dataset), args.split)
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out = Path(args.out) if args.out else ROOT.parent / "target" / "assistant-eval" / f"{ts}-{args.split}"
    out.mkdir(parents=True, exist_ok=False)

    preds = call_parser(TestClient(app), rows)
    metrics, records, errors = evaluate(rows, preds)
    metrics["split"] = args.split
    metrics["dataset"] = str(Path(args.dataset).resolve())
    metrics["generatedAtUtc"] = ts

    def dump(name: str, items: list[dict]) -> None:
        with (out / name).open("w", encoding="utf-8", newline="\n") as f:
            for it in items:
                f.write(json.dumps(it, ensure_ascii=False) + "\n")

    dump("predictions.jsonl", records)
    dump("errors.jsonl", errors)
    (out / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    md = summary_md(metrics, errors, args.split, args.max_errors)
    (out / "summary.md").write_text(md, encoding="utf-8")
    if args.emit_e2e:
        dump("e2e-cases.jsonl", [{"id": r["id"], "category": r["category"], "text": r["text"], "context": row["context"],
                                  "note": row.get("note"),
                                  "expected": r["gold"], "parseResult": r["pred"]}
                                 for r, row in zip(records, rows)])
    print(md[: md.index("## 错误样例")] if "## 错误样例" in md else md)
    print(f"[out] {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
