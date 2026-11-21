# r6 mixed QPS correction (JTL recompute, no re-run)

Evidence: `baseline200-r6-mixed-t200-20261115110827/results.jtl`  
Tool: `infra/jmeter/summarize-jtl.ps1` (byLabel denominator fixed).

## Sample identity

| Check | Result |
|---|---|
| Total JTL rows | 1992 |
| Rows with non-empty `URL` (HTTP samplers) | 1992 |
| Transaction / parent samples | 0 |
| Labels | MIX-detail 200 + MIX-purchase 200 + MIX-poll 1592 = **1992** |

Whole-run window = last sample `timeStamp` − first sample `timeStamp` = **14.306 s** (same as `requestQps` span; `window.json` emitSpanMs=14306).

## Corrected per-API QPS (authoritative)

Denominator for every label: **14.306 s**.

| Label | n | windowSec | QPS (n/window) | P50 | P95 | P99 |
|---|---:|---:|---:|---:|---:|---:|
| MIX-detail | 200 | 14.306 | **13.98** | 1781 | 3402 | 5637 |
| MIX-purchase | 200 | 14.306 | **13.98** | 3291 | 5471 | 6387 |
| MIX-poll | 1592 | 14.306 | **111.28** | 149 | 829 | 1372 |
| **All HTTP** | **1992** | **14.306** | **139.24** | 205 | 3479 | 5273 |

## Optional: label-active-interval QPS (not comparable across APIs)

| Label | activeWindowSec (start-ts span) | activeQps |
|---|---:|---:|
| MIX-detail | 0.108 | 1851.85 ← previously misreported as “QPS” |
| MIX-purchase | 6.121 | 32.67 |
| MIX-poll | 10.570 | 150.61 |

Do not use activeQps for band capacity tables.
