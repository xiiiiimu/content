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
# API: async MQ + fault-hooks; Hikari/Redis via env; pipeline scan off; purchase accept defers MQ send
pwsh -File infra/jmeter/run-java-commerce.ps1 -Package -SkipAssistant

# Optional MySQL headroom for 200-thread storms
docker exec -e MYSQL_PWD=11111 content-mysql-1 mysql -uroot -e "SET GLOBAL max_connections=500;"

# Correctness
pwsh -File infra/jmeter/correctness-short.ps1
pwsh -File infra/jmeter/correctness-barrier-redelivery.ps1

# Seed OUTSIDE formal window (CSV under target/jmeter-data — not committed)
pwsh -File infra/jmeter/seed-commerce-data.ps1 -Buyers 200 -Stock 500 -Prefix b200 -PaidOrders 80
pwsh -File infra/jmeter/seed-commerce-data.ps1 -Buyers 200 -Stock 200 -Prefix b200m

# Preflight then steady QPS (loops=-1, DurationSec>0). Warmup excluded from window.
pwsh -File infra/jmeter/run-commerce-jmeter.ps1 -Scenario sign -Threads 200 -RampSec 15 -DurationSec 20 -Loops 0 `
  -MetaJson target/jmeter-data/meta-<id>.json -Band baseline200-r6 -TimeoutMs 20000
pwsh -File infra/jmeter/run-commerce-jmeter.ps1 -Scenario mixed -Threads 200 -RampSec 8 -DurationSec 0 -Loops 1 `
  -MetaJson target/jmeter-data/meta-<id>.json -Band baseline200-r6 -TimeoutMs 20000

# Full baseline pack
pwsh -File infra/jmeter/run-baseline-200.ps1 -Threads 200 -DurationSec 20 -RampSec 15

# Scenario C TPS (MySQL unique commits; same window math for settle / buyer / giveaway-auto)
pwsh -File infra/jmeter/run-tps-window.ps1 -Buyers 100 -Stock 100 -WindowSec 20
pwsh -File infra/jmeter/run-tps-giveaway-auto.ps1 -Buyers 20

# Distributed 5k/10k (requires injectors)
# see infra/jmeter/distributed/README.md
```

Config before/after for sign/mixed: `config-before-after-sign-mixed.md`. r4 syserr classification: `baseline200-r4-sign-mixed-syserr-classification.md`.

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

## Baseline 200 (r6, authoritative for sign/mixed pass)

Machine: single-box JMeter 5.6.3 + Java 21. TimeoutMs=20000 unchanged. Steady: DurationSec=20, RampSec=15. Mixed: Loops=1, RampSec=8. Seeds: `r6s80bj` (sign), `r6mq7p9` (mixed). JVM: pool=120, jdbc socket=60s, pipeline.scan=false, redis io/cpu=8, consume-threads=20, scan-delay=50ms; purchase accept defers MQ send.

| Scenario | Samples | Req QPS | P50 | P95 | P99 | OK | Biz | Sys | peakHttp | peakSettle |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| sign (r6) | 1152 | 106.8 | 1077 | 6049 | 9988 | 1152 | 0 | **0** | 200 | 0 |
| mixed (r6) | 1992 | 139.2 | 205 | 3479 | 5273 | 1992 | 0 | **0** | 200 | 20 |

Mixed by label (r6, **whole-run windowSec=14.306**; all 1992 rows are HTTP samplers with URL; 200+200+1592=1992). See `baseline200-r6-mixed-qps-correction.md`.

| Label | n | windowSec | QPS | P50 | P95 | P99 | activeQps (optional) |
|---|---:|---:|---:|---:|---:|---:|---:|
| MIX-detail | 200 | 14.306 | **13.98** | 1781 | 3402 | 5637 | 1851.85 (start-span 0.108s; not for capacity) |
| MIX-purchase | 200 | 14.306 | **13.98** | 3291 | 5471 | 6387 | 32.67 |
| MIX-poll | 1592 | 14.306 | **111.28** | 149 | 829 | 1372 | 150.61 |

Reconcile after MQ drain: sold=paid=succReq=ents=**200**/200 (`baseline200-r6-mixed-reconcile.txt`). High tails retained (**sign P99 ≈ 10s**; purchase p99≈6.4s) — not closed by widening TimeoutMs. JVM keeps **`pipeline.scan-enabled=false`** for commerce load (see `config-before-after-sign-mixed.md`).

### Prior r4 archive (retained; sign/mixed failed bar)

Summary JSON: `baseline200-summary-20261115100806.json`. Evidence dirs `baseline200-r4-*`.

| Scenario | Samples | Req QPS | P50 | P95 | P99 | OK | Biz | Sys | peakHttp | peakSettle |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| detail | 798 | 99.3 | 1413 | 6081 | 9041 | 798 | 0 | **0** | 200 | 0 |
| purchase | 427 | 55.2 | 3007 | 10556 | 11562 | 427 | 0 | **0** | (see host-metrics) | |
| poll | 2590 | 282.8 | 450 | 1231 | 1696 | 2590 | 0 | **0** | | |
| order | 1743 | 180.1 | 758 | 2702 | 5021 | 1743 | 0 | **0** | | |
| sign | 1075 | 107.9 | 1300 | 7019 | 10903 | 1072 | 0 | 3 | | |
| flash | 200 | 372.4 | 6762 | 8383 | 8606 | 80 | 120 | **0** | 200 | |
| mixed | 1651 | 57.2 | 1080 | 12656 | 17787 | 1612 | 0 | 39 | 200 | |

Flash reconcile: sold=paid=ents=**80**/80. Intermediate: r5 sign sys=0 / mixed 4 TIMEOUT; r5b consume-threads=48 worsened to 20 sys — retained under `baseline200-r5*` / `baseline200-r5b*`.

### Root cause → fix (sign/mixed)

1. **r4 503/500**: JDBC `socketTimeout=5000` + pool starvation + pipeline scan → raised socket/connect timeouts, pool=120, `pipeline.scan-enabled=false`.
2. **r5 MIX-purchase Read timed out**: accept path blocked on sync RocketMQ send under 200-way storm; Lettuce 2/2 starved Lua reserve → defer MQ to `SalePendingScanner` (50ms), Redis io/cpu=8. Raising consume-threads to 48 increased `lockCampaign` contention (r5b) — kept at 20.

### TPS windows (MySQL unique commits; r6)

Same measurement contract: window `[t0,tWindowEnd)` commits / exact seconds = steady; through-drain / full wall = full TPS. Deduped by unique order / refund row for the run’s campaign.

| Metric | Window | Steady / burst | Full / note | Evidence |
|---|---|---|---|---|
| Settle (PAID) | 20.107s; backlog 0→19; drain 9.996s | **3.78 TPS** (76 in window) | **3.10 TPS** (100 through drain / 32.223s) | `tps-window-20261115111219` |
| Buyer refund | 16.676s | **1.20 TPS** (20/20) | sample OK | same |
| Giveaway auto-refund | commit span 0.956s; wall 3.909s | **18.83** = 18 commits in 0.956s **提交段吞吐** (not sustained steady-window) | **4.60** = 含等待的全程吞吐 (18/3.909s); smoke 1 refund+1 notify OK | `tps-giveaway-auto-20261115111403` |

Legacy r4 **4.82 TPS / ~20s / 100 单**: taken from wall that **included ~0.7s drain** (fullTps style: 100/≈20.744s), not steady-in-window. Corrected split above. Giveaway route was 404 on `/api/materials/{post}/giveaways`; fixed via `POST /api/sale-debug/giveaway-auto-refund` (fault-hooks, same service path as IT).

Reconcile TPS settle campaign: sold=80, orders PAID|REFUNDED=100, active purchase ents=80 after 20 buyer refunds. Giveaway: winners keep GIVEAWAY ents; PURCHASE revoked on auto-refund.

## Bands 5,000 / 10,000

| Band | Status |
|---|---|
| baseline 200 | **Passed** (r6: sign+mixed sys=0, reconcile OK, three TPS windows valid; QPS correction applied) |
| 5,000 | **待验** — injector inventory empty on this workstation |
| 10,000 | **待验** — same |

Available here: control host `LAPTOP-FUI34S6T` (16 vCPU / ~16 GB), JMeter 5.6.3, actual concurrent launch **200**. Missing: remote injector list, per-node JMeter/Java confirmations, SUT URL reachable from injectors. Copy-paste shard/thread tables and launch commands: `infra/jmeter/distributed/README.md`.

Do not report configured thread sum as capacity when launch failed.

## Boundaries

- Payment / refund: **simulated**
- FFmpeg container cleanup: **未验证**
- PR #15 remains **open**

## Earlier archives (non-authoritative)

- `band5k-*` / `band10k-*` @200 before Hikari/JMX fixes — retained for CONNECT_FAIL comparison only.
- `baseline200-retest-*` — first post-Tomcat pass; residual 503 under pool=16.
- Empty JTLs (`loopchk3`/`loopchk4`) — JMX `__P` compile failure; do not use.
- `baseline200-r4-*` — pre-fix archive for sign/mixed syserr.
- `baseline200-r5*` / `r5b*` — intermediate JDBC/consume-thread experiments; JTLs retained.
