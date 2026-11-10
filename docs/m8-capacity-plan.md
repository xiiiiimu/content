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

## 关注图：为何不是 200×20=4000

配置 `readers×followsPerReader` 只是**基础边**。Harness 另有：

1. 每读者再关注前 `min(5,authors)` 热点作者（已在基础边内则幂等，不增边）  
2. sampler `9001`（即 reader 1）再关注全部 authors（与 1 重叠）

因此 DB `FOLLOWING` 边与空历史 **BACKFILL** 任务数 ≈ **4000 + 非重叠热点边**（实测全量 ≈ **4879**），不是造数错误。

## 冻结：实验矩阵（reuse suite）

**同一 mode：真实 follow/publish 造数一次 → coverage → 多负载点复用。**  
档间：drain 积压 + 清理 likes/stats；**不**跨 mode 复用（新 JVM/Testcontainers）。  
`clients=8,duration=60` 仅为矩阵 A 的**一个负载点**，`capacityCeilingClaim=false`。

### A/B/C 在同一次 mode 进程内

默认 `CONTENT_PERF_SUITE=reuse` 负载点：

| 顺序 | clients | durationSec | 含义 |
| ---: | ---: | ---: | --- |
| 1 | 8 | 60 | 同条件对照负载点（非上限） |
| 2–5 | 4/8/16/24 | 30 | 并发阶梯 |
| 6 | 8 | 600 | 稳定性长跑 |

三 mode（push/pull/hybrid）各跑 **一次** reuse 进程。自动队列当前 **暂停**（见 `docs/perf-evidence/capacity/QUEUE-PAUSED.md`）。

### 种子分段计时（`seed-timing.json`）

| 字段 | 含义 |
| --- | --- |
| `followCallMs` | follow API 调用 |
| `emptyHistoryBackfillDrainMs` | 空历史 BACKFILL drain |
| `publishAcceptDrainMs` | publish+accept+worker drain（真实链路） |
| `visibilityWaitMs` | `FollowingFeed.read` 可见性轮询（可按 `CONTENT_PERF_VISIBILITY_EVERY` 采样；**不**改为直灌） |
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

## 验收现状（如实，2026-11-09）

| 项 | 状态 | 证据 |
| --- | --- | --- |
| PUSH 全量 reuse：多档阶梯 + 8×600（10min）稳定性 | **已测** GREEN | `docs/perf-evidence/capacity/push-capacity-20261109-220307-GREEN/`（fix tip `37e1e21`） |
| 三模式小规模对照（约 20×10×40） | **已有** | `docs/perf-evidence/push-*` / `pull-*` / `hybrid-*`（对照噪声，**非**容量） |
| HYBRID 全量单负载点（8×60） | 有一轮有效记录 | `docs/perf-evidence/capacity/hybrid-capacity-20261109-102732/`（非 reuse 全矩阵） |
| 完整规模跨模式容量（push+pull+hybrid 各跑满 reuse） | **未验收** | — |
| 最高容量 / 天花板宣称 | **不宣称** | `capacityCeilingClaim=false` |

队列暂停：`docs/perf-evidence/capacity/QUEUE-PAUSED.md`。暂不追加 PULL/HYBRID 全套实验。

### Delivery claim SQL 修复（审查摘要）

- **根因**：`exhausted`/`candidate` 的 OR + `attempt` 过滤走 PRIMARY，扫 DONE 堆，烧 5s claim TX（未抬超时）。
- **修复**：`V9` `(state,attempt,next_retry_at|lease_until,id)`；claim 路径拆分扫描；**过期 RUNNING 优先于 NEW**，避免 NEW 积压饿死接管；租约/token/DEAD/replay 语义不变。
- **待检查（不改）**：`RelayMapper`（outbox）仍为 OR 形态——仅记项，不凭语法相似直接改。
- **Runner**：`run-baseline.ps1` 在 Maven stderr 下用 `Continue` 取 `$LASTEXITCODE`，避免伪失败；不用 `cmd /c`（非 ASCII 用户路径）。

## 明确不做

- 不把 20×10×40 结果乘比例外推  
- 不在 CI 默认开启 `PerfHarnessIT`  
- ~~不开始 M9 / TrendGuard~~ → **M9 已验收合入** main `0872acc`（`docs/m9-trendguard.md`；汇总 `docs/m0-m9-acceptance.md`）
- 本文件不再追加容量实验；M8 已 merge-commit 合入 main `a4765c1`
- **永久**：不追加 PULL/HYBRID 全量 reuse，不宣称最高容量  


