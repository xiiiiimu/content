| run | target rps | achieved rps | ok | 4xx | 5xx | transport err | dropped iters | p50 ms | p95 ms | p99 ms | max ms | row lock waits | row lock time ms | hikari acquires | avg acquire ms | max pending |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| warmup | 20 | 20.0 | 1201 | 0 | 0 | 0 | 0 | 34.5 | 66.6 | 220.9 | 789.6 | 6 | 167 | 3669 | 0.5 | 0 |
| b200 | 200 | 133.9 | 8369 | 0 | 6 | 0 | 3625 | 2696.2 | 4660.0 | 5833.1 | 9271.6 | 7481 | 530976 | 25168 | 442.6 | 184 |
