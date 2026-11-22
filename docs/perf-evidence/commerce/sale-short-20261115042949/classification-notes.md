# Classification notes (retained; pre-fix run)

Run: `sale-short-20261115042949` (stock=8, buyers=24, duplicateExtra=1 → 48 attempts).

## Reported summary (as archived)

| Field | Value |
|---|---|
| acceptProcessingCount | 16 |
| uniqueSuccessOrders | 8 |
| businessRejects | 0 |
| systemErrors | 32 |
| reconcile | sold=paid=ents=8, no oversell |

## Classification doubt (why numbers look wrong)

1. **`FeedErrors` did not include `SaleController`**, so Redis Lua business rejects (`SOLD_OUT`, `ALREADY_RESERVED`, …) thrown as `FeedFailure(409, code)` were handled as uncaught exceptions → **HTTP 500** instead of 409 + `{code}`. The short-load script’s `catch` then bucketed them as **systemErrors**.
2. Script only wrote `raw.ndjson` on the happy path after poll; failures never recorded HTTP status / business code per attempt → **cannot reclassify the 32 rows from raw alone**.
3. Duplicate same-`clientKey` replays that returned an already-terminal request were counted toward success when polled, not as **idempotent replay**.
4. Reconcile (MySQL) remains trustworthy for oversell / one-order-per-user; only the **HTTP/biz classification columns** are suspect.

## Follow-up

- Fix: register `SaleController` on `FeedErrors`; rewrite short classifier (HTTP + body `code` / request `state` / timeout / connect).
- Re-run: `infra/jmeter/correctness-short.ps1` (and/or fixed `load-material-commerce-short.ps1`). Keep this directory as the original suspect evidence.
