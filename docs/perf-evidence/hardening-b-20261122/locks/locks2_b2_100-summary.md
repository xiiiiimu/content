| run | target rps | achieved rps | ok | 4xx | 5xx | transport err | dropped iters | p50 ms | p95 ms | p99 ms | max ms | row lock waits | row lock time ms | hikari acquires | avg acquire ms | max pending |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| warmup | 20 | 20.0 | 1200 | 0 | 0 | 0 | 0 | 36.3 | 72.5 | 440.7 | 918.2 | 11 | 334 | 3665 | 0.9 | 0 |
| b100 | 100 | 99.9 | 6000 | 0 | 0 | 0 | 0 | 51.2 | 132.7 | 233.4 | 437.8 | 675 | 20937 | 18067 | 0.6 | 13 |
| c100 | 100 | 98.6 | 5907 | 0 | 11 | 0 | 83 | 516.4 | 3667.2 | 5307.3 | 7373.4 | 3404 | 358222 | 17793 | 275.4 | 184 |
