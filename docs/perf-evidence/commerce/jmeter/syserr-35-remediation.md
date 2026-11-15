# Detail GET system-error remediation (200-thread baseline)

## Before (archived)

Evidence: `band5k-detail-detail-t200-20261115060906`
- samples=200 (one iter/thread; loops mangled)
- systemErrors=35 — **all** `CONNECT_FAIL` (`HttpHostConnectException` / Connection refused to 127.0.0.1:28080)
- HTTP 业务码/超时: 0; 其它系统错误: 0

Root causes found later:
1. Tomcat accept backlog too small under connect storm → Connection refused
2. JMX `${__P(csvVars,userId,token,clientKey)}` → InvalidVariableException (4 args) → empty JTL on some runs
3. Formal window lacked warmup; seed overlapped measurement intent

## Fix applied

- `run-java-commerce.ps1`: accept-count=500, max-connections=10000, threads.max=400, min-spare=50
- `SaleHttpInFlightFilter` + `peakHttpInFlight` (separate from settle in-flight)
- `commerce-sale.jmx`: CSV header-driven vars (no comma-in-`__P`)
- `run-commerce-jmeter.ps1`: seed/preflight/warmup outside formal window; quoted `-J`; exit-code not polluted by stdout; loops=-1 when DurationSec>0
- `FeedConfiguration`: Hikari was hard-capped at 16 (ignored `spring.datasource.hikari.*`); now `CONTENT_HIKARI_MAX_POOL` / `-Dcontent.hikari.max-pool` (commerce sets 80). Residual 503 under load = `FEED_UNAVAILABLE` from Hikari wait / DataAccess — not Connection refused.

## After

| Evidence | CONNECT_FAIL | Sys | Notes |
|---|---:|---:|---|
| `loopchk5-detail-t20-20261115081041` | 0 | 0 | multi-iter OK |
| `baseline200-r4-detail-t200-20261115094647` | **0** | **0** | formal 200-thread detail |
| `baseline200-r4-flash-flash-t200-20261115095903` | 0 | 0 | sold=paid=ents=80 |

Authoritative table: `INDEX.md` + `baseline200-summary-20261115100806.json`.
