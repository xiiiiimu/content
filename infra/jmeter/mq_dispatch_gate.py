"""Resource gate for MQ dispatch tests and load tiers (no thresholds are relaxed on failure).

Checks: no running Testcontainers container, Windows free physical memory >= --min-free-mib,
NameServer/Broker Docker health "healthy", and N consecutive successful Broker clusterList probes.
Writes a JSON record and exits 1 when any check fails.

  python infra/jmeter/mq_dispatch_gate.py --out target/<run>/gate-<label>.json --label red
  python infra/jmeter/mq_dispatch_gate.py --out ... --memory-only   # tier start/end free memory record
"""
import argparse, json, subprocess, sys, time
from datetime import datetime, timezone


def run(cmd, timeout=60):
    started = time.monotonic()
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace")
        return p.returncode, p.stdout, p.stderr, round((time.monotonic() - started) * 1000)
    except subprocess.TimeoutExpired:
        return -1, "", "timeout", round((time.monotonic() - started) * 1000)


def free_memory():
    rc, out, err, _ = run(["powershell", "-NoProfile", "-Command",
                           "$o=Get-CimInstance Win32_OperatingSystem;"
                           "'{0} {1}' -f [math]::Round($o.FreePhysicalMemory/1024),[math]::Round($o.TotalVisibleMemorySize/1024)"])
    free, total = (int(x) for x in out.split())
    return {"atLocal": datetime.now().astimezone().isoformat(), "freeMiB": free, "totalMiB": total}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--label", default="")
    ap.add_argument("--min-free-mib", type=int, default=3072)
    ap.add_argument("--probes", type=int, default=3)
    ap.add_argument("--probe-interval", type=float, default=5.0)
    ap.add_argument("--memory-only", action="store_true")
    a = ap.parse_args()
    record = {"label": a.label, "memoryBefore": free_memory(), "minFreeMiB": a.min_free_mib}
    failures = []
    if not a.memory_only:
        rc, out, _, _ = run(["docker", "ps", "--filter", "label=org.testcontainers=true", "--format", "{{.ID}} {{.Image}} {{.Status}}"])
        running = [l for l in out.splitlines() if l.strip()]
        record["runningTestcontainers"] = running
        if rc != 0 or running:
            failures.append("testcontainers-running" if running else "docker-ps-failed")
        health = {}
        for c in ("content-namesrv-1", "content-broker-1"):
            rc, out, _, _ = run(["docker", "inspect", "--format", "{{.State.Health.Status}} {{.State.Health.FailingStreak}}", c])
            health[c] = out.strip()
            if rc != 0 or not out.startswith("healthy"):
                failures.append(f"{c}-not-healthy")
        record["dockerHealth"] = health
        probes = []
        for i in range(a.probes):
            rc, out, err, ms = run(["docker", "exec", "content-broker-1", "sh", "mqadmin", "clusterList", "-n", "namesrv:9876"], timeout=30)
            ok = rc == 0 and "content-broker" in out
            probes.append({"probe": i + 1, "ok": ok, "rc": rc, "elapsedMs": ms, "at": datetime.now().astimezone().isoformat()})
            if not ok:
                failures.append(f"broker-probe-{i + 1}-failed")
                break
            if i + 1 < a.probes:
                time.sleep(a.probe_interval)
        record["brokerProbes"] = probes
    record["memoryAfter"] = free_memory()
    if min(record["memoryBefore"]["freeMiB"], record["memoryAfter"]["freeMiB"]) < a.min_free_mib:
        failures.append("free-memory-below-threshold")
    record["failures"] = failures
    record["pass"] = not failures
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(record, f, ensure_ascii=False, indent=2)
    print(json.dumps({"pass": record["pass"], "failures": failures, "freeMiB": record["memoryAfter"]["freeMiB"],
                      "probeMs": [p["elapsedMs"] for p in record.get("brokerProbes", [])]}))
    return 0 if record["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
