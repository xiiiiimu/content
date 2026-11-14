# Material commerce JMeter plans

Formal load tests for limited-sale commerce. **Do not commit real credentials**; seed scripts write local CSV under `target/jmeter-data/` (gitignored via `target/`).

## Layout

| Path | Role |
|---|---|
| `commerce-sale.jmx` | Parameterized plan: detail / purchase / poll / order / sign / flash / mixed |
| `seed-commerce-data.ps1` | Register buyers, follow author, create material+campaign; emit CSV |
| `run-commerce-jmeter.ps1` | CLI runner → JTL + HTML + host metrics + summary |
| `summarize-jtl.ps1` | Classify HTTP/business codes; QPS/latency from JTL |
| `run-java-commerce.ps1` | Start API with async sale MQ + fault-hooks |
| `correctness-short.ps1` | Re-run 8×24×2 short test with full classification |
| `correctness-barrier-redelivery.ps1` | Hold-barrier blacklist + post-commit pre-ACK redelivery |
| `run-formal-bands.ps1` | Smoke → 5k/10k bands for scenarios A–D (records actual concurrency) |

## Required Java flags (async MQ)

```
--content.sale.sync-settle=false
--content.sale.producer-enabled=true
--content.sale.consumer-enabled=true
--content.sale.scan-enabled=true
--content.sale.manual-settle=true
--content.sale.fault-hooks=true
--content.sale.namesrv=127.0.0.1:29876
--content.sale.topic=content-sale
```

JMeter binary (local, not committed): `tools/apache-jmeter-5.6.3/bin/jmeter.bat`

## Parameters (user properties)

| Property | Meaning | Example |
|---|---|---|
| `apiBase` | API origin | `http://127.0.0.1:28080` |
| `threads` | Thread group size | `100` |
| `rampSec` | Ramp-up seconds | `10` |
| `durationSec` | Scheduled duration (0 = once) | `30` |
| `scenario` | `detail` / `purchase` / `poll` / `order` / `sign` / `flash` / `mixed` | `flash` |
| `csvUsers` | Buyer CSV path | `target/jmeter-data/buyers.csv` |
| `campaignId` | Sale campaign id | from seed |
| `stock` | Campaign stock (seed) | `8` |
| `timeoutMs` | HTTP timeout | `10000` |

## Smoke then formal bands

1. Seed + smoke (`threads=20`, short duration).
2. Band `5000` and `10000`: set `-Jthreads=` to target; if OS/JVM cannot sustain, record **actual peak active threads** and in-flight from `/api/sale-debug/inflight`.
3. Distributed: same JMeter/Java versions; shard buyer CSV; absolute start epoch via `-JstartEpochMs=`.

## Boundaries

Payment/refund simulated. FFmpeg container cleanup unverified.
