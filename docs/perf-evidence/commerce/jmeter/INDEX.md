# Material commerce JMeter evidence (PR #15)

Formal load uses Apache JMeter 5.6.3 CLI (`infra/jmeter/`). Payment/refund simulated. FFmpeg container cleanup unverified.

## Correctness (pre-JMeter)

| Evidence | Result |
|---|---|
| `../sale-short-20261115042949` | **Retained suspect**: `systemErrors=32` misclassified (SaleController missing from `FeedErrors` + PS `HttpResponseException` isa `HttpRequestException`) |
| `../sale-short-classified-20261115054231` | **GREEN**: 8 SUCCEEDED, 8 IDEMPOTENT_REPLAY, 32 `BIZ_HTTP:SOLD_OUT`, 0 system; sold=paid=ents=8 |
| `../correctness-barrier-redelivery-20261115053617` | **GREEN**: hold→blacklist→REJECTED/BLACKLISTED; seat resold; fail-after-commit redelivery → 1 order / 1 entitlement |

## How to re-run

```powershell
# API with async MQ + fault-hooks
pwsh -File infra/jmeter/run-java-commerce.ps1 -Package -SkipAssistant

# Correctness
pwsh -File infra/jmeter/correctness-short.ps1
pwsh -File infra/jmeter/correctness-barrier-redelivery.ps1

# Seed (CSV under target/jmeter-data — not committed)
pwsh -File infra/jmeter/seed-commerce-data.ps1 -Buyers 200 -Stock 80 -Prefix smoke

# Smoke / bands
pwsh -File infra/jmeter/run-commerce-jmeter.ps1 -Scenario detail -Threads 20 -DurationSec 12 -Loops 0 -MetaJson target/jmeter-data/meta-<id>.json -Band smoke
pwsh -File infra/jmeter/run-commerce-jmeter.ps1 -Scenario flash -Threads 50 -Loops 1 -MetaJson ... -Band smoke
pwsh -File infra/jmeter/run-formal-bands.ps1 -MaxLocalThreads 200   # want 5k/10k; records actual launch

# Scenario C TPS window (MySQL unique commits)
pwsh -File infra/jmeter/run-tps-window.ps1 -Buyers 80 -Stock 80 -WindowSec 25
```

## Concurrency reporting rule

Configured `-Jthreads` is **not** a capacity ceiling. Each run’s `host-metrics.json` records configured threads, CPU/mem snapshots, and server in-flight via `GET /api/sale-debug/inflight` (`SaleFaultHooks` AtomicLong around settle). If host cannot open 5,000/10,000 threads, report **actual launch threads** and bottleneck (local single-box JMeter/JVM/OS).

## Smoke (this machine)

| Run | Samples | Notes |
|---|---|---|
| `smoke-detail-t20-20261115055952` | 20 | detail OK |
| `smoke-flash-t50-20261115060305` | 50 | ~299 req/s; biz rejects vs OK split; 0 system |
| `smoke-mixed-t10-20261115060414` | 25 | detail→purchase→poll |

Failed empty JTLs from early scheduler bugs archived under same folder (0-sample runs) — do not use for capacity claims.

## Bands 5k / 10k (this host)

**Want** 5000 / 10000 threads. **Actual launch** on this box: **200** (single JMeter JVM / OS thread ceiling — not a product capacity claim). In-flight measured via `GET /api/sale-debug/inflight`.

### band5k @ 200 threads (representative)

| Run | Samples | Req QPS | OK | Biz | Sys |
|---|---:|---:|---:|---:|---:|
| detail | 200 | 281 | 165 | 0 | 35 |
| purchase | 200 | 412 | 83 | 117 | 0 |
| order | 200 | 382 | 200 | 0 | 0 |
| sign | 200 | 637 | 83 | 117 | 0 |
| flash | 200 | 820 | 83 | 117 | 0 |
| mixed (50 thr) | 125 | 63 | 100 | 25 | 0 |

Note: early duration-window runs used `-Jloops=-1` mangled by `jmeter.bat` arg parsing → one iteration/thread (samples≈threads). Runner now invokes via `cmd /c` with quoted props; re-run duration QPS for multi-iter windows. Flash/mixed once-through numbers remain valid.

### band10k @ 200 threads

First pass (`band10k-*-202611150617*`) returned all-biz (stale/seed mismatch under Redis `FEED_UNAVAILABLE` during parallel follow). Treat as **failed archive**; retest after seed follow retries. Distributed 10k requires sharded CSV + clock-synced `startEpochMs`.

### Scenario C TPS

`run-tps-window.ps1` counts MySQL unique PAID / SUCCEEDED refund rows in a wall window. Giveaway-auto-refund TPS: use mq-demo evidence when sample insufficient under load seed pressure.

## Bottlenecks observed

1. Local JMeter cannot open 5k/10k threads — cap and report actual.
2. Parallel register/follow can trip Redis → `FEED_UNAVAILABLE` (seed now retries follow).
3. Auth username charset `[a-zA-Z0-9_]{3,32}` — seed sanitizes prefixes.
