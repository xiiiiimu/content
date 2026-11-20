# Audit: in-stock 5k barrier run → why only 357 orders

Evidence root: `purchase-5k-stock-t5000-purchase-t5000-20261116025653`  
Index: `purchase-post-5k-20261116024415.json` (stock leg)  
**PR #15 kept open. Original JTL / logs / DB rows / uncommitted tooling left intact.**

## 1. Scope identifiers

| Field | Value |
|---|---|
| campaignId | **72** |
| postId | 85 |
| stockCap | 5000 |
| buyers CSV | `target/jmeter-data/buyers-p5kstksml9.csv` (5000 rows; reused logins) |
| JTL | `…/results.jtl` (5000 POST-purchase) |
| formalWindow | startEpochMs=1794769015422 → endEpochMs=1794769210507 (wall 196.955s) |
| barrier release | releaseEpochMs=1794769149396; readyCount=5000; timedOut=false |
| emit span | firstSampleTs=1794769151668 → lastSampleTs=1794769184432 (**32764 ms**) |
| Tomcat at run | threads.max=5000, accept-count=5000 (unchanged for this audit) |

### Final count: **357**, not 358

Queried **only** `campaign_id=72` at audit time (PROCESSING left = **0**):

| Metric | Value |
|---|---:|
| `material_sale_campaigns.sold_count` | 357 |
| `COUNT(DISTINCT id)` PAID orders | 357 |
| `COUNT(DISTINCT user_id)` PAID buyers | 357 |
| `COUNT(DISTINCT reservation_id)` SUCCEEDED requests | 357 |
| ACTIVE PURCHASE entitlements | 357 |
| PROCESSING remaining | 0 |
| Redis `sale:{72}:stock` | 4606 (= 5000 − 394 held/consumed) |
| Redis `sale:{72}:u:*` / `r:*` keys | 394 / 394 |

**358 does not appear** in `purchase-post-5k-20261116024415.json`, `purchase-5k-report.json`, INDEX, or live MySQL. The “357 or 358” ambiguity was uncertainty across summaries, not two different query results. All durable counters agree on **357**.

Paid-in-formal-window (`paid_at_ms ∈ [t0,t1)`) = **0**; all 357 `paid_at_ms` fall **after** `formalWindow.endEpochMs` (settle drained post-emit). That is a window definition effect, not an order-count effect.

### Limited-stock run (separate, campaign **71**)

| Metric | Value |
|---|---:|
| stockCap / sold | 100 / **61** |
| SUCCEEDED requests / PAID orders / ents | 61 / 61 / 61 |
| unique buyers | 61 |
| PROCESSING | 0 |

## 2. Row linkage limits (old evidence)

JTL columns: `timeStamp,elapsed,label,responseCode,responseMessage,threadName,…,URL,Latency,IdleTime,Connect`  
**Missing for join:** `responseData`, `failureMessage` (always empty), `clientKey`, `reservationId`, `userId`.

`-Jjmeter.save.saveservice.response_data_on_error=true` was set, but the saved JTL schema has **no `responseData` column** and empty `failureMessage` — bodies were not persisted.  
Tomcat **access log was not enabled**; no per-request server access log for this window.  
Windows TCP counters were **not snapshotted** during the run.

Therefore **row-level** JTL ↔ MySQL ↔ access-log join is **impossible** on this archive. Below is an **aggregate** disposition matrix grounded in JTL codes + campaign-72 tables + Redis + `byClient` semantics.

### Aggregate disposition (5000 JTL rows)

JTL HTTP classes (rows):

| Class | n |
|---|---:|
| Connection refused (`HttpHostConnectException` / getsockopt) | 3606 |
| Read timed out (`SocketTimeoutException`) | 322 |
| HTTP 500 | 478 |
| HTTP 503 | 11 |
| HTTP 401 | 12 |
| HTTP 200 | 571 |
| **Total** | **5000** |

MySQL campaign 72: **only** SUCCEEDED×357 (no REJECTED/PROCESSING rows).

Cross-table (rows = JTL class; columns = durable outcome for **campaign 72**). Cells that cannot be proven per-row are marked with evidence basis:

| JTL class \ durable | 无持久化请求 (camp 72) | PROCESSING | REJECTED (by reason) | SUCCEEDED (+ order) | Evidence gap |
|---|---:|---:|---:|---:|---|
| HTTP 200 | **214** (cross-campaign `byClient` replay; **not** camp-72 rows) | 0 | 0 | **357** | Cannot split 571→(214,357) per JTL line (no clientKey/reservationId in JTL). Split proven by arithmetic + DB: 1876 CSV buyers already had `client_key=jm-{userId}` on **other** campaigns before emit; `prior_and_on_72=0`; HTTP200=571; camp72 rows=357 → **214** idempotent old-campaign returns. |
| 明确业务拒绝 (409 etc.) | 0 | 0 | 0 | 0 | No 409 in this JTL |
| Connection refused | 3606 | 0 | 0 | 0 | No server accept; no DB row expected |
| Read timed out | ≤322 | 0 | 0 | ≤? | **Gap:** may include late server work; cannot prove which timeouts still wrote rows without reservationId in JTL |
| HTTP 5xx (500+503) | ≤489 | 0 | 0 | ≤? | Bodies missing; Redis shows **394** reserves vs **357** MySQL ⇒ **≥37** Lua-OK / MySQL-missing (likely subset of 500s). Exact mapping unknown |
| HTTP 401 | 12 | 0 | 0 | 0 | Auth failed before purchase persist |
| **Column sums (known)** | | 0 | 0 | **357** | |

**Checksum:** 3606+322+478+11+12+571 = **5000**. Every JTL row is classified; durable SUCCEEDED column = **357**.

### 583 non-system samples vs 357 orders

Non-sys (not connect / timeout / 5xx) = **583** = **571×200 + 12×401**.

| Piece | n | Meaning |
|---|---:|---|
| HTTP 401 | 12 | No purchase row |
| HTTP 200 idempotent old `clientKey` | **214** | `SaleService.purchase` `byClient(userId,clientKey)` is **not campaign-scoped**; returns prior campaign’s request as HTTP 200 |
| HTTP 200 new camp-72 accept | **357** | Durable SUCCEEDED + PAID + entitlement |
| **Sum** | **583** | |

So 583 − 357 = **226** = 214 (wrong-campaign idempotent success) + 12 (401). **Not** silent drops of camp-72 orders.

Code pointer: `SaleService.purchase` → `db.byClient(userId, clientKey)` before campaign reserve; seed CSV uses stable `clientKey=jm-{userId}` across campaigns.

### Client-fail then still paid?

**Cannot confirm any** JTL Connection-refused / timeout row that still created a camp-72 order: no join key. Aggregate: refused rows did not need a server handler; Redis reserve count (394) is only slightly above MySQL (357), consistent with **no large** “client failed but server committed” population beyond the 37 Lua/MySQL gaps (those are server-side 5xx class, not connect refused).

## 3. CONNECT_FAIL taxonomy (3606)

All **3606** non-HTTP connect failures share the same message stem:

`Connect to 127.0.0.1:28080 [/127.0.0.1] failed: Connection refused: getsockopt`

| Subtype | n |
|---|---:|
| Connection refused | **3606** |
| Connection reset | **0** |
| Connect timeout | **0** |
| Local socket (addr-in-use / no buffer / broken pipe) | **0** |
| Read timed out (separate class) | 322 |

`Connect` column on refused samples: min=1 ms, max=3919 ms, avg≈1040 ms (failed handshake latency, not app RTT).

Emit vs refused (seconds after barrier release): refused dominates secs 2–5 and again 22–35; HTTP 200/500 appear in the same windows when the accept path was partially open. **peakHttpInFlight=477** ≪ Tomcat max 5000 → pool threads were **not** the pin this run.

**Verdict (evidence-based):** failures sit at **connection admission** (OS/listen backlog / accept queue under barrier thundering herd), not inside purchase business logic. Application did accept up to ~477 concurrent HTTP; most of the 5000 syns never became HTTP.  
**Missing for stronger proof:** Tomcat access log, `netstat`/`Get-NetTCPConnection` / `TcpListenOverflows` snapshots at release. **Do not** further raise Tomcat threads/timeouts until paced control finishes (per request).

## 4. Paced control (completed)

Same 5000 buyers (`buyers-p5kstksml9.csv` → re-login into `buyers-p5kpacedls7w.csv`), **new** campaign **73** stock=5000, Tomcat **unchanged** 5000/5000, **barrier OFF**, `RampSec=100` (target ≈50/s), `-ClientKeyMode unique`, response bodies + `sample_variables=userId,effClientKey,reservationId` enabled. Barrier burst archive retained.

| Metric | Barrier stock (camp 72) | Paced control (camp 73) |
|---|---:|---:|
| configured / peakActive | 5000 / **5000** | 5000 / **1090** (ramp; threads finish before all start) |
| emit span / achieved rate | 32.8s / ~153 start-span QPS-ish | **82.8s emit** / **60.42/s** (target 50/s) |
| HTTP 200 | 571 | **4290** |
| Connection refused | 3606 | **710** (all still `Connection refused: getsockopt`) |
| Read timeout / 5xx / 409 | 322 / 489 / 0 | **0 / 0 / 0** |
| Durable PAID / SUCCEEDED / ents | **357** | **4290** |
| settleFull TPS / drain / backlog peak | 1.45 / 11.7s / 133 | **11.75** / **179.1s** / **3147** |
| reconcile | ok | **ok** |
| JTL↔DB join | impossible (no keys) | **4290/4290** HTTP200 have `reservationId` (+ userId, effClientKey) |

Index JSON: `purchase-post-5k-paced-20261116080940.json`  
Dir: `purchase-5k-paced-t5000-purchase-t5000-20261116080328`

**Interpretation:** Spreading arrivals (~50–60/s) cuts Connection refused 3606→710 and raises durable first-buys 357→4290 under the **same** Tomcat sizing. Remaining 710 refused are still admission-path (not reset/timeout). Cross-campaign `byClient` was neutralized via unique clientKeys (補采); barrier run’s 214 phantom HTTP200s do not apply here.

### Re-run / 补采 checklist

**Applied on paced run:** response_data=true; sample_variables; TCP listen/established snapshot before emit (`paced-tcp-before.json`).  
**Still missing on barrier archive (and optional next):** Tomcat accesslog; `TcpListenOverflows` / SynReceived time series at barrier release; campaign-scoped clientKey in seed (`jm-c{campaignId}-{userId}`) as alternative to `-ClientKeyMode unique`.

## SQL used

```sql
-- campaign 72 only
SELECT stock_cap, sold_count FROM material_sale_campaigns WHERE id=72;
SELECT state, COUNT(*), COUNT(DISTINCT user_id), COUNT(DISTINCT reservation_id)
  FROM material_purchase_requests WHERE campaign_id=72 GROUP BY state;
SELECT COUNT(DISTINCT id), COUNT(DISTINCT user_id)
  FROM material_orders WHERE campaign_id=72 AND state='PAID';
SELECT COUNT(*) FROM material_entitlements e
  JOIN material_orders o ON e.source_kind='PURCHASE' AND e.source_id=o.id
 WHERE o.campaign_id=72 AND e.state='ACTIVE';
```
