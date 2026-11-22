| run | target rps | achieved rps | ok | 4xx | 5xx | transport err | dropped iters | p50 ms | p95 ms | p99 ms | max ms | row lock waits | row lock time ms | hikari acquires | avg acquire ms | max pending |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| warmup | 20 | 20.0 | 1200 | 0 | 0 | 0 | 0 | 35.0 | 63.1 | 168.7 | 761.7 | 6 | 90 | 1265 | 1.2 | 0 |
| b200 | 200 | 165.6 | 10295 | 0 | 0 | 0 | 1706 | 2317.5 | 2646.2 | 2793.3 | 3828.6 | 9913 | 378489 | 10336 | 1067.6 | 184 |
