# Distributed 5,000 / 10,000 load (JMeter CLI)

Single-box baseline uses **actual launch threads ≤ host cap** (see `baseline200-r4-*` / `INDEX.md`). Full 5k/10k requires N load generators.

## Prerequisites

| Item | Requirement |
|---|---|
| JMeter | **5.6.3** on every injector (`tools/apache-jmeter-5.6.3`) |
| Java | Same major version as server (21) |
| Clock | NTP sync; injectors use shared `-JstartEpochMs=` |
| Network | All injectors reach `ApiBase` (not only localhost) |
| CSV | Non-overlapping buyer shards (`shard-buyers.ps1`) |
| Threads | Sum of `-Jthreads` across injectors = target band |

## Shard CSV

```powershell
pwsh -File infra/jmeter/distributed/shard-buyers.ps1 `
  -SourceCsv target/jmeter-data/buyers-<id>.csv `
  -Shards 5 -OutDir target/jmeter-data/shards
# → buyers-shard-00.csv … buyers-shard-04.csv
```

## Launch one injector

```powershell
# Example: 5 injectors × 1000 threads = 5000
pwsh -File infra/jmeter/run-commerce-jmeter.ps1 `
  -ApiBase http://<SUT>:28080 `
  -Scenario flash -Threads 1000 -RampSec 0 -Loops 1 `
  -StartEpochMs <sharedEpoch> `
  -CsvUsers target/jmeter-data/shards/buyers-shard-00.csv `
  -CampaignId <id> -PostId <id> `
  -Band band5k-flash-inj0 -WarmupThreads 0 -SkipPreflight
```

Steady QPS (duration window, multi-iter):

```powershell
pwsh -File infra/jmeter/run-commerce-jmeter.ps1 `
  -ApiBase http://<SUT>:28080 -Scenario detail `
  -Threads 1000 -RampSec 20 -DurationSec 60 -Loops 0 `
  -CsvUsers ... -MetaJson ... -Band band5k-detail-inj0 -TimeoutMs 20000
```

## Orchestration checklist

1. Seed once on control host (outside window); shard CSV; publish `meta-*.public.json` (no tokens in git).
2. Preflight from each injector (`preflight-commerce.ps1`).
3. Agree `startEpochMs` = now+15s; start all injectors before that instant.
4. After flash: drain RocketMQ / PROCESSING=0; MySQL reconcile stock/orders/ents.
5. Between bands: new campaign + CSV; never reuse depleted stock for flash oversell checks.

## Status on this workstation

| Band | Status |
|---|---|
| baseline 200 | **Executed** (`baseline200-r4-*`, CONNECT_FAIL=0 on detail) |
| 5,000 | **待验** — needs ≥1 remote injector (or N local processes with sharded CSV totaling 5000 if host allows) |
| 10,000 | **待验** — same |

Record actual Σ threads if short of target; never report configured sum as capacity when launch failed.
