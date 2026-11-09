# M8 容量验收计划（冻结）

基线：`main` `f416a99`（PR #8 合并）。分支：`codex/m8-capacity-acceptance`。  
**不自动合并。不开始 M9。** 小规模对照（20×10×40）不得外推为本机容量结论。

## 已有台账

| 来源 | 用途 |
| --- | --- |
| `infra/perf/README.md` | 固定配额与 ops 定义 |
| `infra/perf/run-baseline.ps1` | 单次模式跑次入口 |
| `PerfHarnessIT` | 计时前 mode-coverage + corpus 断言；`valid:true` |
| `docs/perf-evidence/*` + `VOIDED.md` | 小规模有效/作废证据（对照噪声，非容量） |

## 冻结：数据规模与负载

| 项 | 冻结值 | 说明 |
| --- | ---: | --- |
| authors | **100** | README 全量配额 |
| postsPerAuthor | **50** | 语料 5000 帖 |
| readers | **200** | |
| followsPerReader | **20** | + 前 5 热点；sampler 9001 关注全作者 |
| write:read | **1:9** | like : feed_read |
| hot likes | top-10 帖 | |
| JVM | `-Xms512m -Xmx512m` | 与小规模同条件，暴露堆边界 |
| 中间件 | Testcontainers MySQL 8.4 + Redis 7.4 单节点 | 非 Cluster |
| 种子顺序 | rebuild → modes → **follow-first** → drain → publish/accept/worker → coverage → steady | |

## 冻结：实验矩阵

### A. 同条件三模式对照

| 参数 | 值 |
| --- | ---: |
| modes | push / pull / hybrid（各 1 次主对照，同参数） |
| durationSec | **60** |
| clients | **8** |

### B. 并发阶梯（固定 hybrid，同数据规模）

| clients | durationSec |
| ---: | ---: |
| 4 | 30 |
| 8 | 30 |
| 16 | 30 |
| 24 | 30 |

阶梯在 A 完成后执行；若某档触发停止条件，更高档不跑。

### C. 稳定性长跑

| 参数 | 值 |
| --- | ---: |
| mode | hybrid（或 A/B 中仍健康的最高档 mode+clients） |
| clients | B 中未触发停止条件的最大档（默认目标 8） |
| durationSec | **600** |

## 冻结：延迟与正确性目标（观测门禁，非产品 SLA）

相对小规模有效对照（P95≈800ms、err%=0、thr≈17 ops/s @ clients=8）仅作**本机噪声参考**，不作外推系数。

| 门禁 | 条件 | 动作 |
| --- | --- | --- |
| coverage | `mode-coverage.json` 断言失败或 `valid` 缺失 | **轮次作废**，修后重跑 |
| 正确性 | feed 结果集 ≠ 种子语料 / PUSH·PULL 路径断言失败 | **轮次作废** |
| 错误率 | `errorRate` ≥ 0.05（harness 硬失败）或稳态 ≥ 0.01 | ≥0.05 停止矩阵；≥0.01 记边界并停止加并发 |
| 积压 | 稳态结束 `backlogNewOrRunningEnd` 持续上升且 drain 不可收敛 | 停止加并发；记录恢复曲线 |
| 资源 | JVM OOM / 进程被杀 / 主机无响应 | 停止；该规模即本机硬边界 |
| 种子超时 | 单模式种子+coverage 超过 **180 min** | 停止该规模；降级仅作边界说明，**不宣称容量通过** |

延迟数字一律如实记录 P50/P95/P99；**不设“必须低于 X ms”的通过线**——容量结论是本机可支撑规模与退化点，不是 SLA 认证。

## 每轮流程

1. 跑 harness（coverage 在计时前）  
2. 确认 `summary.json` 含 `"valid":true` 与 `mode-coverage.json`  
3. 记录 thr、P95/P99、errorRate、backlog*、资源快照、drain 恢复  
4. 原始目录留在 `target/perf-evidence/`，有效轮次同步到 `docs/perf-evidence/capacity/`  
5. 失败 → RED 复现 → GREEN 修复 → REFACTOR 清理 → 重跑同一冻结参数

## 明确不做

- 不把 20×10×40 结果乘比例外推  
- 不在 CI 默认开启 `PerfHarnessIT`  
- 不开始 M9 / TrendGuard  
- 不自动合并 PR  
