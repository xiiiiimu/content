# DeliveryMapper.exhausted timeout investigation

Failure artifact: `push-capacity-20261109-111937-RED` (pre-fix tip `e069d8c` / code path before attempt-aware index).  
Queue remains paused (`QUEUE-PAUSED`). Prep-stage failure ≠ formal load metric.

## Exception (abbrev)

- `org.springframework.dao.QueryTimeoutException` / `MySQLTimeoutException: Statement cancelled due to timeout or client request`
- Call chain: `DistributionWorker.tick` → `DeliveryStore.claim` → `DeliveryMapper.exhausted` → `drainPipeline`

## SQL (bound)

```sql
SELECT id AS taskId, event_id AS eventId, kind, author_id AS authorId, post_id AS postId,
       follower_id AS followerId, follow_epoch AS followEpoch, payload, lease_token AS token,
       attempt, lease_generation AS generation, scan_upper_follower_id AS upper,
       last_follower_id AS lastFollower, last_post_id AS lastPost, delivery_epoch AS deliveryEpoch
FROM feed_delivery_tasks
WHERE ((state='NEW' AND next_retry_at<=CURRENT_TIMESTAMP(6))
    OR (state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6)))
  AND attempt>=?
ORDER BY id
LIMIT 100
FOR UPDATE SKIP LOCKED
```

Bind: `max` = `content.pipeline.max-attempts` default **5**.

## Timeout configuration (not raised as the fix)

| Layer | Value | Source |
| --- | ---: | --- |
| Spring TX on `DeliveryStore` | **5s** | `DeliveryStore` ctor `tx.setTimeout(5)` |
| Shared `feedTransactions` | 5s | `FeedConfiguration` |
| JDBC URL `socketTimeout` / statement queryTimeout | unset (defaults) | `application.yml` |

Cancellation matches **transaction timeout**, not an intentional longer statement budget.

## Schema indexes (before fix)

From `V1__content_baseline.sql`:

- `PRIMARY KEY (id)`
- `UNIQUE uq_delivery_event (event_id)`
- `KEY ix_delivery_retry (state, next_retry_at, id)`
- `KEY ix_delivery_lease (state, lease_until, id)`

**No index covering `attempt`.** Optimizer cannot prove emptiness of `attempt >= max` without walking READY rows (or worse, the whole PK).

## EXPLAIN ANALYZE (probe MySQL 8.4, 80k DONE + 20k NEW, attempt&lt;5)

| Shape | Access | Rows examined (actual) | Time |
| --- | --- | ---: | ---: |
| OR + `attempt>=5` (production SQL) | **PRIMARY index scan** | **100000** | ~51ms |
| Split NEW-only, no attempt index | PRIMARY index scan | 100000 | ~41ms |
| OR + `ix_delivery_retry_attempt (state,attempt,next_retry_at,id)` | range on attempt index | 0 | ~0.5ms |
| Split NEW + attempt index | range on attempt index | 0 | ~0.01ms |
| OR candidate `attempt<5` (still) | PRIMARY index scan | ~80001 | ~34–94ms |
| Split NEW candidate + `ix_delivery_retry` | range on retry index | 1 | ~0.25ms |

Conclusion: OR across NEW/RUNNING plus non-selective `attempt` filter → **full PRIMARY scan including DONE heap**. Under capacity DONE growth + concurrent workers + Docker IO, that scan burns the 5s claim TX → `QueryTimeoutException` on `exhausted`. Not lock-wait primary: `FOR UPDATE SKIP LOCKED` skips held rows; DONE-row lock probe shows no `Innodb_row_lock_waits` growth.

## Classification

| Hypothesis | Verdict |
| --- | --- |
| Slow scan (PRIMARY full scan / missing attempt selectivity) | **Root cause** |
| Lock wait on contested READY rows | Secondary only; SKIP LOCKED avoids DONE-row blocking |
| Host CPU/IO alone without scan shape | Amplifier, not root |

## Protocol constraints for fix

Keep lease / token / exhaust→DEAD / replay semantics. Prefer **attempt-aware indexes + OR split for candidate/exhausted plans**; do **not** first raise TX timeout.

## RED gate

`DeliveryExhaustedClaimIT.exhaustedPlanAndClaimMustNotFullScanPrimaryAcrossDoneHeap` asserts EXPLAIN `key != PRIMARY` and `type != index` on the exhausted predicate after seeding DONE+NEW volume.  
RED commit: `c662343` (pre-fix OR SQL → PRIMARY).

## GREEN fix (protocol-preserving)

1. `V9__delivery_attempt_claim_indexes.sql` — `(state, attempt, next_retry_at, id)` / `(state, attempt, lease_until, id)`.
2. Split `exhausted` / `candidate` into NEW then RUNNING selects (no cross-state OR) so the optimizer can use retry/lease/attempt indexes instead of PRIMARY.
3. Lease token bump, `attempt` accounting, DEAD on exhaust, replay audit, and old-token rejection unchanged (`DeliveryWorkerIT` covers takeover / last-claim crash / DEAD / stale token).

## Post-fix PUSH reuse

- Tip: `37e1e21`. Evidence: `docs/perf-evidence/capacity/push-capacity-20261109-220307-GREEN/`.
- Prep (not load metric): follow ~283s, empty-history BACKFILL drain ~611s, publish+accept+drain ~1666s; **no** `exhausted` QueryTimeout.
- All reuse load points `valid=true`, `errors=0` (incl. 8×600). Queue remains paused; pull/hybrid/ladder not started.

## Targeted review (pre-commit)

| Check | Result |
| --- | --- |
| Protocol (lease bump / SKIP LOCKED / DEAD on exhaust / replay audit / stale token) | Unchanged; `DeliveryWorkerIT` gates |
| Plan after V9 + split | `type=range key=ix_delivery_retry_attempt` (GREEN evidence) |
| Ordering NEW-then-RUNNING vs global `ORDER BY id` | Fairness only; not a correctness/protocol break |
| TX timeout | Still 5s; not raised |
| Runner stderr false-fail | Fixed via `$ErrorActionPreference='Continue'` + `$LASTEXITCODE` |
| Outbox `RelayMapper` OR | Noted residual; out of this fix scope |
| M8 claim language | PUSH multi-rung + 10min stability only; no cross-mode full acceptance; no ceiling claim |
