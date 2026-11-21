# System-error classification (pre-fix; retained)

Run: `band5k-detail-detail-t200-20261115060906`  
Target: `GET /api/materials/{post}/sale-campaigns/current`  
Configured threads: 200 · samples: 200 · reported systemErrors: 35

## Per-failure class

| Class | Count | Evidence |
|---|---:|---|
| CONNECT_FAIL (`HttpHostConnectException` / Connection refused) | 35 | All failed rows; elapsed 3–6 ms |
| HTTP 5xx | 0 | — |
| TIMEOUT | 0 | — |
| Business HTTP 4xx | 0 | — |

Sample message (identical for all 35):

```
Non HTTP response code: org.apache.http.conn.HttpHostConnectException
Non HTTP response message: Connect to 127.0.0.1:28080 [/127.0.0.1] failed: Connection refused: getsockopt
```

Timestamps cluster at run start (~1794694189198–231) while `allThreads=200` — accept backlog / Tomcat not yet absorbing the concurrent connect storm (not application business rejects).

## Fix plan (same config retest)

1. Raise Tomcat `max` / `accept-count` / `max-connections` for load JVM.
2. Preflight readiness + short warm-up before formal window; longer ramp for 200 threads.
3. Re-run detail @200 with multi-iter duration; archive as `*-retest-*` beside this folder.
