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
# API: async MQ + fault-hooks + Tomcat backlog + Hikari via CONTENT_HIKARI_MAX_POOL (FeedConfiguration)
pwsh -File infra/jmeter/run-java-commerce.ps1 -Package -SkipAssistant

# Optional MySQL headroom for 200-thread storms
docker exec -e MYSQL_PWD=11111 content-mysql-1 mysql -uroot -e "SET GLOBAL max_connections=500;"

# Correctness
pwsh -File infra/jmeter/correctness-short.ps1
pwsh -File infra/jmeter/correctness-barrier-redelivery.ps1

# Seed OUTSIDE formal window (CSV under target/jmeter-data — not committed)
pwsh -File infra/jmeter/seed-commerce-data.ps1 -Buyers 200 -Stock 500 -Prefix b200 -PaidOrders 80

# Preflight then steady QPS (loops=-1, DurationSec>0). Warmup excluded from window.
pwsh -File infra/jmeter/run-commerce-jmeter.ps1 -Scenario detail -Threads 200 -RampSec 15 -DurationSec 20 -Loops 0 `
  -MetaJson target/jmeter-data/meta-<id>.json -Band baseline200 -TimeoutMs 20000

# Full baseline pack
pwsh -File infra/jmeter/run-baseline-200.ps1 -Threads 200 -DurationSec 20 -RampSec 15

# Scenario C TPS (MySQL unique commits)
pwsh -File infra/jmeter/run-tps-window.ps1 -Buyers 100 -Stock 100 -WindowSec 20
pwsh -File infra/jmeter/run-tps-giveaway-auto.ps1 -Buyers 6

# Distributed 5k/10k (requires injectors)
# see infra/jmeter/distributed/README.md
```

## Concurrency reporting rule

Configured `-Jthreads` is **not** a capacity ceiling. Each run’s `host-metrics.json` records configured threads, peak active threads, `peakHttpInFlight` (servlet filter) and `peakSettleInFlight` (settle hooks) separately via `GET /api/sale-debug/inflight`.

## Detail GET 35 system errors — before / after

See `syserr-35-remediation.md` and `band5k-detail-detail-t200-20261115060906/syserr-classification.md`.

| Phase | Evidence | CONNECT_FAIL | Other system | Notes |
|---|---|---:|---:|---|
| Before | `band5k-detail-detail-t200-20261115060906` | **35/35** | 0 | Connection refused; one-iter (loops mangled) |
| Loop fix | `loopchk5-detail-t20-20261115081041` | 0 | 0 | samples=470≫20; multiIterOk |
| After (formal) | `baseline200-r4-detail-t200-20261115094647` | **0** | **0** | Hikari max-pool=80 via env; Tomcat accept-count=500 |

Root causes fixed: Tomcat accept backlog; JMX `__P(csvVars,…)` 4-arg compile fail; FeedConfiguration hard-cap Hikari=16 (now `CONTENT_HIKARI_MAX_POOL`); seed/warmup outside formal window; `loops=-1` for duration runs.

Residual under mixed load: HTTP 503 `FEED_UNAVAILABLE` (Hikari wait / DataAccess) and rare TIMEOUT — not Connection refused.

## Baseline 200 (r4, authoritative)

Machine: single-box JMeter 5.6.3 + Java 21. Window: DurationSec=20, RampSec=15, TimeoutMs=20000, loops=-1 (steady). Flash: loops=1, startEpochMs sync. Summary JSON: `baseline200-summary-20261115100806.json`.

| Scenario | Samples | Req QPS | P50 | P95 | P99 | OK | Biz | Sys | peakHttp | peakSettle |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| detail | 798 | 99.3 | 1413 | 6081 | 9041 | 798 | 0 | **0** | 200 | 0 |
| purchase | 427 | 55.2 | 3007 | 10556 | 11562 | 427 | 0 | **0** | (see host-metrics) | |
| poll | 2590 | 282.8 | 450 | 1231 | 1696 | 2590 | 0 | **0** | | |
| order | 1743 | 180.1 | 758 | 2702 | 5021 | 1743 | 0 | **0** | | |
| sign | 1075 | 107.9 | 1300 | 7019 | 10903 | 1072 | 0 | 3 | | |
| flash | 200 | 372.4 | 6762 | 8383 | 8606 | 80 | 120 | **0** | 200 | |
| mixed | 1651 | 57.2 | 1080 | 12656 | 17787 | 1612 | 0 | 39 | 200 | |

Flash reconcile: sold=paid=ents=**80**/80 (sell-out rate 60% biz reject). Evidence dir `baseline200-r4-flash-flash-t200-20261115095903`; `baseline200-r4-flash-reconcile.txt`.

### TPS windows (MySQL unique commits)

| Metric | Window | Value | Evidence |
|---|---|---|---|
| Settle (PAID distinct) | 20s wall | **4.82 TPS** (100 unique) | `tps-window-20261115100548` |
| Buyer refund SUCCEEDED | ~18s | **1.09 TPS** (20/20) | same |
| Giveaway auto-refund | — | **Pending sample** (giveaway POST 404); correctness via `MaterialCommerceRefundBlacklistIT` + mq-demo | `tps-giveaway-auto-20261115100715` |

Reconcile after TPS seed path: sold=paid=ents=80 (stock-limited campaign in that script).

## Bands 5,000 / 10,000

| Band | Status |
|---|---|
| baseline 200 | **Executed** (r4) |
| 5,000 | **待验** — no remote injectors on this workstation; kit ready in `infra/jmeter/distributed/` |
| 10,000 | **待验** — same |

Do not report configured thread sum as capacity when launch failed. Actual single-box launch ceiling used here: **200**.

## Earlier archives (non-authoritative)

- `band5k-*` / `band10k-*` @200 before Hikari/JMX fixes — retained for CONNECT_FAIL comparison only.
- `baseline200-retest-*` — first post-Tomcat pass; residual 503 under pool=16.
- Empty JTLs (`loopchk3`/`loopchk4`) — JMX `__P` compile failure; do not use.
