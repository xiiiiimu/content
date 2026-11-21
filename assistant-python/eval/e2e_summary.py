"""Join e2e-cases.jsonl (gold + recorded parse) with AssistantEvalE2EIT output and count
commands Java actually executed although they should not have been.

  .venv/Scripts/python.exe eval/e2e_summary.py <run-dir> [results.jsonl]
    expects <run-dir>/e2e-cases.jsonl and <run-dir>/e2e-results.jsonl, writes e2e-summary.md/.json

Definitions
- 非法执行 (ILLEGAL, target 0): a state-changing command (giveaway created / subscription created)
  where gold action != EXECUTE, or the executed intent differs from the gold intent.
- 订阅目标错误: subscription created on an account/comment other than the replied-to feedback.
- 参数偏差执行: gold EXECUTE giveaway was created, but rule/deadline/count/license differ from gold.
  Sub-flag 越权放大: license escalated DOWNLOAD->COMMERCIAL, or gold conditions dropped.
- 误执行搜索: search ran although gold is not SEARCH/EXECUTE (no state change, reply only).
- Java 拦截: parser emitted a command, Java refused it (task error) — validation working.
Exit code 1 when ILLEGAL > 0.
"""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_eval import pred_action, tree_view  # noqa: E402


def read(p: Path) -> list[dict]:
    return [json.loads(x) for x in p.read_text(encoding="utf-8").splitlines() if x.strip()]


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    run = Path(sys.argv[1])
    cases = {c["id"]: c for c in read(run / "e2e-cases.jsonl")}
    results = read(Path(sys.argv[2]) if len(sys.argv) > 2 else run / "e2e-results.jsonl")
    out, counts = [], Counter()
    blocked = Counter()
    for r in results:
        c = cases[r["id"]]
        g = c["expected"]
        if r.get("harness"):
            counts["HARNESS_ERROR"] += 1
        executed = ("GIVEAWAY" if r.get("giveawayCreated") else
                    "SUBSCRIBE" if r.get("subscriptionCreated") else
                    "SEARCH" if r.get("searchExecuted") else None)
        flags = []
        state_change = executed in ("GIVEAWAY", "SUBSCRIBE")
        if state_change and (g["action"] != "EXECUTE" or g["intent"] != executed):
            flags.append("ILLEGAL")
        if r.get("subscriptionCreated") and not r.get("subscriptionOnParent"):
            flags.append("SUB_WRONG_TARGET")
        if executed == "SEARCH" and (g["action"] != "EXECUTE" or g["intent"] != "SEARCH"):
            flags.append("MIS_SEARCH")
        if executed == "GIVEAWAY" and g["action"] == "EXECUTE" and g["intent"] == "GIVEAWAY":
            gv, gp = r["giveaway"], g["params"]
            tv = tree_view(gv["ruleTreeJson"])
            diffs = [f for f, a, b in [("conditions", tv["conditions"], gp["conditions"]), ("op", tv["op"], gp["op"]),
                                      ("k", tv["k"], gp["k"]), ("deadlineMs", gv["deadlineMs"], gp["deadlineMs"]),
                                      ("winnerCount", gv["winnerCount"], gp["winnerCount"]),
                                      ("rewardLicense", gv["rewardLicense"], gp["rewardLicense"])] if a != b]
            if diffs:
                flags.append("PARAM_DEVIATION:" + "/".join(diffs))
            if gp["rewardLicense"] == "DOWNLOAD" and gv["rewardLicense"] == "COMMERCIAL":
                flags.append("ESCALATION:license")
            if set(gp["conditions"]) - set(tv["conditions"]):
                flags.append("ESCALATION:conditions_dropped")
        if pred_action(c["parseResult"]) == "EXECUTE" and executed is None:
            err = (r.get("lastError") or r.get("reply") or "").strip()
            blocked[err.split(":")[-1].strip()[:80] or "NO_EFFECT"] += 1
            flags.append("JAVA_BLOCKED")
        for f in flags:
            counts[f.split(":")[0]] += 1
        out.append({"id": r["id"], "category": c["category"], "text": c["text"], "gold": f"{g['intent']}/{g['action']}",
                    "parsed": f"{c['parseResult']['intent']}/{pred_action(c['parseResult'])}", "javaExecuted": executed,
                    "note": c.get("note") or "", "giveaway": r.get("giveaway"), "postLicenseCommercial": r.get("postLicenseCommercial"),
                    "lastError": r.get("lastError"), "flags": flags})

    illegal = [o for o in out if "ILLEGAL" in o["flags"]]
    summary = {"cases": len(results), "illegal_executed": len(illegal), "counts": dict(counts),
               "java_blocked_reasons": dict(blocked.most_common())}
    lines = [f"# E2E: parse result -> Java execution ({len(results)} cases)", "",
             f"- **非法指令被执行 (ILLEGAL): {len(illegal)}**（期望 0）"]
    lines += [f"- {k}: {v}" for k, v in sorted(counts.items()) if k != "ILLEGAL"]
    lines += ["", "## Java 拦截原因", ""] + [f"- {k}: {v}" for k, v in blocked.most_common()]
    lines += ["", "## 被标记的样例", "", "| id | category | text | gold | parsed | java executed | flags |", "|---|---|---|---|---|---|---|"]
    for o in out:
        if any(f.split(":")[0] in ("ILLEGAL", "SUB_WRONG_TARGET", "ESCALATION", "PARAM_DEVIATION", "MIS_SEARCH", "HARNESS_ERROR") for f in o["flags"]):
            lines.append(f"| {o['id']} | {o['category']} | {o['text'].replace('|', '/')} | {o['gold']} | {o['parsed']} | "
                         f"{o['javaExecuted']} | {', '.join(o['flags'])} |")
    md = "\n".join(lines) + "\n"
    (run / "e2e-summary.md").write_text(md, encoding="utf-8")
    (run / "e2e-summary.json").write_text(json.dumps({**summary, "rows": out}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(md)
    return 1 if illegal else 0


if __name__ == "__main__":
    raise SystemExit(main())
