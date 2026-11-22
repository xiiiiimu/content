| run | target rps | achieved rps | ok | 4xx | 5xx | transport err | dropped iters | p50 ms | p95 ms | p99 ms | max ms | row lock waits | row lock time ms | hikari acquires | avg acquire ms | max pending |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| warmup | 20 | 20.0 | 1201 | 0 | 0 | 0 | 0 | 38.0 | 75.2 | 221.0 | 789.2 | 6 | 140 | 1265 | 1.3 | 0 |
| b100 | 100 | 100.0 | 6001 | 0 | 0 | 0 | 0 | 50.2 | 192.4 | 352.7 | 464.6 | 782 | 32427 | 6068 | 6.2 | 9 |
| c100 | 100 | 100.0 | 6001 | 0 | 0 | 0 | 0 | 43.0 | 63.5 | 109.7 | 249.0 | 131 | 1998 | 6087 | 0.0 | 0 |
