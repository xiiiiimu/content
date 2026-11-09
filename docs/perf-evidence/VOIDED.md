# 作废的性能结果

以下目录保留原始 `summary.json` / `config*.txt` / `commit.txt`，但**不得再作为有效对照或验收依据**。

| 目录 | 作废原因 |
| --- | --- |
| `hybrid-smoke`（仅 target，未入库） | 种子顺序错误：先 publish 后 follow，PUSH Redis inbox 空，P95≈2214ms 无效 |
| `hybrid-20261109-043943` | 计时前未断言模式覆盖（inbox / PULL SQL / 结果集）；有效性未核验 |
| `push-20261109-044435` | 同上 |
| `pull-20261109-044908` | 同上 |
| `hybrid-20261109-045554` | 同上；且为池扩容噪声复测 |
| `push-20261109-050142` | 同上 |
| `pull-20261109-050637` | 同上 |

有效结果须满足：`summary.json` 含 `"valid":true`，同目录存在通过断言的 `mode-coverage.json`，并由本批修复后的 `PerfHarnessIT` 产出。

主对照（有效）：`hybrid-20261109-054301`、`push-20261109-060107`、`pull-20261109-060546`。
