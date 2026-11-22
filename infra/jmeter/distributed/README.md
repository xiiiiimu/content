# Distributed 5,000 / 10,000 load (JMeter CLI)

Single-box baseline uses **actual launch threads ≤ host cap** (see `baseline200-r6-*` / `INDEX.md`). Full 5k/10k requires N load generators with non-overlapping CSV shards.

## Status on this workstation (2026-11-15)

| Item | Value |
|---|---|
| Control / only known host | `LAPTOP-FUI34S6T` (16 logical CPUs, ~16 GB RAM) |
| JMeter | 5.6.3 at `tools/apache-jmeter-5.6.3` |
| Java (SUT) | 21 |
| Injector inventory file | **missing** — no `injectors.txt` / `hosts.txt` / `CONTENT_JMETER_INJECTORS` |
| Remote injectors reachable | **0** |
| Actual concurrent launch ceiling used | **200** (r6 single-box) |
| Band 5,000 | **待验** |
| Band 10,000 | **待验** |

Do **not** report Σ configured threads as capacity when injectors were not launched.

## Prerequisites (each injector)

| Item | Requirement |
|---|---|
| JMeter | **5.6.3** (`tools/apache-jmeter-5.6.3`) |
| Java | Same major as SUT (**21**) |
| Clock | NTP sync; shared `-JstartEpochMs=` for flash |
| Network | Reach `ApiBase` (not only localhost on control host) |
| CSV | Non-overlapping shards from `shard-buyers.ps1` |
| Threads | Sum of `-Jthreads` across injectors = target band |

## Startup checks (copy)

```powershell
# On control host
$ApiBase = 'http://<SUT>:28080'   # isolated test env only
$JMeterHome = 'E:\content\tools\apache-jmeter-5.6.3'
java -version
& "$JMeterHome\bin\jmeter.bat" -v
Invoke-RestMethod "$ApiBase/actuator/health/readiness"
# Optional MySQL headroom
docker exec -e MYSQL_PWD=11111 content-mysql-1 mysql -uroot -e "SET GLOBAL max_connections=1000;"

# On each injector (replace HOST)
# 1) same JMeter 5.6.3 + Java 21
# 2) clock: w32tm /query /status   (or chronyc tracking)
# 3) curl/IRM $ApiBase/actuator/health/readiness
# 4) free RAM ≥ ~2GB + ~1MB×localThreads (rule of thumb)
```

## Thread / shard allocation tables

### Band 5,000 (example: 5 injectors × 1000)

| Injector | Threads | CSV shard | Band tag suffix |
|---:|---:|---|---|
| inj0 | 1000 | `buyers-shard-00.csv` | `-inj0` |
| inj1 | 1000 | `buyers-shard-01.csv` | `-inj1` |
| inj2 | 1000 | `buyers-shard-02.csv` | `-inj2` |
| inj3 | 1000 | `buyers-shard-03.csv` | `-inj3` |
| inj4 | 1000 | `buyers-shard-04.csv` | `-inj4` |
| **Σ** | **5000** | 5000 buyers min | |

### Band 10,000 (example: 10 injectors × 1000, or 5 × 2000)

| Injector | Threads | CSV shard | Band tag suffix |
|---:|---:|---|---|
| inj0…inj9 | 1000 each | `buyers-shard-00` … `09` | `-injN` |
| **Σ** | **10000** | 10000 buyers min | |

Alternate 5×2000: `-Shards 5`, each `-Threads 2000`, shards `00`…`04`.

## CSV sharding

```powershell
# Seed OUTSIDE formal windows (control host); buyers >= band size
pwsh -File infra/jmeter/seed-commerce-data.ps1 -ApiBase $ApiBase `
  -Buyers 5000 -Stock 5000 -Prefix b5k -OutDir target/jmeter-data
# Flash limited stock (separate campaign)
pwsh -File infra/jmeter/seed-commerce-data.ps1 -ApiBase $ApiBase `
  -Buyers 5000 -Stock 500 -Prefix b5kflash -OutDir target/jmeter-data
# Steady/sign/order with paid seeds as needed
pwsh -File infra/jmeter/seed-commerce-data.ps1 -ApiBase $ApiBase `
  -Buyers 5000 -Stock 8000 -Prefix b5kpay -PaidOrders 200 -OutDir target/jmeter-data

pwsh -File infra/jmeter/distributed/shard-buyers.ps1 `
  -SourceCsv target/jmeter-data/buyers-<id>.csv `
  -Shards 5 -OutDir target/jmeter-data/shards-5k
# → buyers-shard-00.csv … buyers-shard-04.csv (non-overlapping)
```

Publish `meta-*.public.json` (no tokens) to injectors; keep token-bearing meta on control only.

## Small-scale dry-run (must pass before 5k)

```powershell
# 2 shards × 20 threads; confirm merged sample count ≈ expected and labels present
$epoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 15000
0..1 | ForEach-Object -Parallel {
  pwsh -File infra/jmeter/run-commerce-jmeter.ps1 `
    -ApiBase $using:ApiBase -Scenario purchase -Threads 20 -RampSec 2 -Loops 1 `
    -CsvUsers "target/jmeter-data/shards-5k/buyers-shard-0$_.csv" `
    -MetaJson target/jmeter-data/meta-<id>.json `
    -Band "dry-inj$_" -WarmupThreads 0 -SkipPreflight -TimeoutMs 20000
} -ThrottleLimit 2
# Expect: each inj ~20 samples; after merge Σ≈40; systemErrors classified; no CONNECT_FAIL to wrong host
```

## Scenario commands (per injector; fill `<…>`)

Shared properties: `$ApiBase`, `$TimeoutMs=20000`, `$BandPrefix=band5k` (or `band10k`), `$Shard=00`, `$Threads=1000`.

```powershell
$Meta = 'target/jmeter-data/meta-<id>.json'
$Csv  = "target/jmeter-data/shards-5k/buyers-shard-$Shard.csv"
$Inj  = "inj$([int]$Shard)"

# A) Single-API steady (duration window; warmup excluded by runner)
pwsh -File infra/jmeter/run-commerce-jmeter.ps1 -ApiBase $ApiBase `
  -Scenario detail -Threads $Threads -RampSec 20 -DurationSec 60 -Loops 0 `
  -CsvUsers $Csv -MetaJson $Meta -Band "$BandPrefix-detail-$Inj" -TimeoutMs 20000
# Repeat scenario ∈ purchase|poll|order|sign with matching CSV/meta (paid CSV for sign/order)

# B) Flash — limited stock, synchronized start; one purchase per account (Loops=1)
$start = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 15000
pwsh -File infra/jmeter/run-commerce-jmeter.ps1 -ApiBase $ApiBase `
  -Scenario flash -Threads $Threads -RampSec 0 -Loops 1 -StartEpochMs $start `
  -CsvUsers $Csv -MetaJson $MetaFlash -Band "$BandPrefix-flash-$Inj" `
  -WarmupThreads 0 -TimeoutMs 20000
# Idempotent replay / multi-purchase = separate run (Loops>1 or duration), not mixed into sell-out check

# C) Mixed flow
pwsh -File infra/jmeter/run-commerce-jmeter.ps1 -ApiBase $ApiBase `
  -Scenario mixed -Threads $Threads -RampSec 15 -DurationSec 0 -Loops 1 `
  -CsvUsers $Csv -MetaJson $Meta -Band "$BandPrefix-mixed-$Inj" -TimeoutMs 20000

# D) Settle / buyer-refund / giveaway-auto TPS — control host MySQL windows (unique commits)
pwsh -File infra/jmeter/run-tps-window.ps1 -ApiBase $ApiBase -Buyers 500 -Stock 500 -WindowSec 20
pwsh -File infra/jmeter/run-tps-giveaway-auto.ps1 -ApiBase $ApiBase -Buyers 40
```

After flash/mixed/purchase: drain `PROCESSING=0`, then reconcile sold / PAID orders / entitlements / refunds / notifications. New campaign + CSV between bands.

## Metrics to record per node / band

- Configured threads, observed peak active threads  
- `peakHttpInFlight`, `peakSettleInFlight` (`/api/sale-debug/inflight`)  
- Emit span, sample counts, host CPU/RAM  
- Per-label QPS via `summarize-jtl.ps1` (**whole-run window denominator**)  
- P50/P95/P99; business rejects vs system errors  
- Steady TPS = unique MySQL commits in fixed window; full TPS includes drain  
- MQ backlog at window end + drain time  

## Orchestration checklist

1. Seed once on control (outside window); shard CSV; distribute public meta.  
2. Preflight each injector (`preflight-commerce.ps1`).  
3. Flash: agree `startEpochMs=now+15s`; start all injectors before that instant.  
4. Drain RocketMQ / PROCESSING; MySQL reconcile.  
5. Oversell, duplicate settle/refund, or undrainable backlog on **5k** → keep failure evidence, **do not** promote to 10k; fix and retest same config.  
6. Isolated test env only; payment/refund remain simulated.

## Missing to execute 5k/10k here

1. Injector host list (IPs/DNS) + SSH/WinRM or shared drive for CSV/JMX  
2. Confirmed JMeter 5.6.3 + Java 21 on each injector  
3. SUT `ApiBase` reachable from injectors (not `127.0.0.1` on laptop alone)  
4. Buyer pool ≥ band size and independent campaigns per write scenario  

Until those exist, bands stay **待验**; reuse this README + `shard-buyers.ps1` unchanged.
