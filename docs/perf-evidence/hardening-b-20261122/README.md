# 第 B 轮报告（2026-11-22）

基线 `beafbea`。按用户要求分主题提交：B1 `e940821`、B2/B2b `4e5fa91`、B3 `33a05f8`、B4 `331b31f`、B5 `623488e`、测量工具 `4fa9ac0`、报告 `d5f59a0`，以及之后的测量工具修复和本次报告更新。
环境：插电、高性能模式，同一时间只有一个会话跑测试或压测。互动压测用 `infra/perf/interactions`（独立库 `content_perf_r1_<phase>_<时刻>`，k6 开放模型，每档 60 s，账号直接写库、token 本地签发）。每个阶段用的 jar、`git diff`、`git status` 和未跟踪源文件副本在 `target/perf-hardening-b-20261122/app-<阶段>.*`（不进 docs）。

| 项 | 状态 | 一句话 |
|---|---|---|
| B1 话题分钟桶热点 | 完成（本地相关 IT 通过，待 CI） | 改为异步聚合；b/c 100 rps 错误从 33/26 降到 0；200 rps 新上限约 150–180 rps，原因是 `post_stats` 热行 |
| B2 计数增量 + 对账 | 完成（含 B2b 语句顺序） | 1M 赞的帖子单次点赞的计数部分 69.4 ms → 0.07 ms；顺带修掉两处既有错误（并发丢计数、重复举报被计数） |
| B3 每次点赞 1 个连接 | 完成 | Hikari 获取次数/请求 3.0 → 1.0 |
| B4 发送端空轮询退避 | 完成 | 空闲 3 s 内空领取 29 → ≤8 次；空闲时整库提交 17.4 → 1.1 次/秒；有信号时立即恢复 |
| B5 抢购缓存同步 | 完成 | 直接结算每单提交 3 → 2；MQ 消费路径按批提交；负载下每单提交约 3.76 → 2.42（−36%），结算吞吐 43–46 → 49–50 单/秒 |
| B6 关注潮 / 推扩散 | 实测完成 | 关注在作者行上串行，约 25–31 次/秒封顶，64 并发时有取连接超时；推扩散约 1000 粉丝/秒，10 万粉丝 95 s。修复记入计划 B7、B8 |

此前缺证据的两项（B4/B5 抢购压测、B6 剩余部分）曾因系统内存告急被 Claude Code 中止；用户释放内存后已补测，见各节。

---

## B1 话题分钟桶：异步聚合

**实现**：点赞/评论事务只写 `topic_event_ledger` 一行（V32 新增 `aggregated_at_ms`：NULL=待聚合，`revoked_at_ms`），不再碰 `topic_minute_buckets`，也不在事务里做风险检测。后台 `TopicAggregator`（`content.trending.aggregator.*`）：
- **租约**：`topic_aggregator_state` 单行，条件 UPDATE 取得租约（owner + lease_until），每批在同一事务里先锁这行再干活，过期的持有者被栅栏挡住，多实例只有一个在聚合。
- **进度与恢复**：每批 `FOR UPDATE SKIP LOCKED` 按发生时间领取待聚合行，加到分钟桶、标记 `aggregated_at_ms`，同一事务提交；崩溃回滚后重做，**幂等**（行被标记前不会被重复计数）。水位 = min(最早待聚合行, now − 在途余量)，记录在状态行。
- **风险检测**：聚合时按话题检测（与原规则相同的一小时窗口），去重记忆在事务提交后才生效。
- **取消点赞（R8）**：未聚合的赞直接删除账本行；已聚合的赞改名为 `rv:<uuid>` 并标 `revoked_at_ms`、重新置为待聚合，由聚合器做一次负修正，`GREATEST(...,0)`，净值不为负，不会减两次。
- **与 C 轮衔接**：聚合水位就是 `TopicRetention` 的“已处理水位”（C-13）；未聚合行永不删除（`retentionKeepsEveryUnfoldedLedgerRow`）。
- 滞后上限可配，`content.trending.aggregator.max-lag-millis` 默认 5000；超过时告警。间隔 1000 ms，批 1000。

**测试**：RED `target/hardening-b/red-b1-*`（新增 `TopicAggregationIT` 13 例，10 例因缺目标行为失败，3 例是守卫）；GREEN `green-b1-*`、`green-b1b-*`。已有 IT 的语义不变，只是在读桶或读风险前先 `aggregator.drain()`（因为计数改为异步，读之前要先聚合），断言值未改：`LikeTopicFlipIT`、`RiskEmitDedupIT`、`TrendingRetentionIT`、`CommentTopicEventIdIT`、`PostTopicIT`、`CommentIdempotencyRaceIT`、`TrendGuardIT`、`TrendGuardRiskIT`；`MySqlMigrationIT` 迁移计数 +1。

**数据滞后（账本写入 → 计入分钟桶）**，`aggregated_at_ms − occurred_at_ms`，after_b1 阶段：

| 话题 | p50 | p95 | p99 | max |
|---|---|---|---|---|
| b50 / c50 | 526–529 | 994–995 | 1033–1035 | 1055–1068 |
| b100 / c100 | 531–536 | 1000–1006 | 1045–1048 | 1081–1087 |
| b200 | 809 | 1762 | 2165 | 2662 |
| c200 | 884 | 2593 | 4670 | **5285** |

100 rps 以内 p99 ≈ 1.05 s（≈ 聚合间隔 1 s）。200 rps 时连接池饱和，c200 最大 5.3 s，超过默认 5 s 一次（日志有 1 条告警）。之后的阶段（B2/B3/B2b 降低了池压力）200 rps 最大 2.3–2.9 s。

**桶净值 = 成功数**：每阶段末按话题核对，全部相等（例 after_b2b：r1b200 10521 = ok 10521，r1c200 11217 = ok 11217；after_b1：r1b200 9303、r1c200 9391）。

## B2 计数增量更新 + 离线对账（R3）

**实现**：`post_stats` 按本事务的受影响行数 ±1（`addStats`，`GREATEST(...,0)`）；没有统计行时用 COUNT 建一次（`createStats`，ON DUPLICATE KEY 再加增量）；后台 `StatsReconciler`（`content.stats.reconcile.*`，默认 60 s 一批 200 帖）在统计行 `FOR UPDATE` 下重数并修正偏差，返回偏差明细。热路径不再有 COUNT。
**B2b**：计数 UPDATE 移到事务最后一条写（账本、outbox、助手入队之后），热行锁只覆盖 UPDATE、一次读和提交。

**顺带发现并修复的既有错误**（RED 在旧代码上失败）：
1. **并发丢计数**：旧的“每次重数”在 READ COMMITTED 下，两个事务都在对方提交前数完，后写的旧值覆盖新值。600 次并发互动后 like_count = 27，实际 28。增量 UPDATE 在行锁下累加，不会丢。
2. **重复举报被计数**：`INSERT ... ON DUPLICATE KEY UPDATE id=id` 在 Connector/J 下对重复行也返回 1，重复举报被当成新举报。改为普通 INSERT，捕获重复键（MySQL 语句级回滚，事务继续）。

**未修复、记录在案**：同一用户对同一帖同时点赞和点踩可能死锁（like 先锁 dislikes 再锁 likes，dislike 相反）。属既有问题，测试中按线程划分用户回避，建议后续单列一项。

**测试**：RED `red-b2-075733`（`PostStatsIncrementIT` 6 例中 5 例失败；`interactionsRunNoCount` 用 performance_schema 摘要证明旧代码每次互动执行 COUNT，32 次）。更早两次 RED 尝试的失败属夹具问题（测试内对账侧车堆内存溢出、点赞/点踩死锁），日志保留在 `red-b2-*`，不算 RED。GREEN `green-b2-080045`（8 个类）；B2b 后 `green-b2b-*`（10 个类）通过。

**单事务探针**（与风险清单实测 A 同法，100 次顺序执行，计数部分 = 统计相关语句耗时）：

| 已有赞 | 改前 总/计数 ms | 改后 总/计数 ms |
|---|---|---|
| 0 | 7.34 / 0.36 | 7.28 / 0.15 |
| 100k | 13.45 / 6.89 | 6.26 / 0.05 |
| 1M | 77.00 / 69.40 | 6.61 / 0.07 |

1M 赞时单次点赞回到无赞时的水平；剩下的约 6 ms 是提交（fsync）。证据 `probe-b2/`。

## B3 每次点赞 1 个连接

可见性检查移入互动事务（同一连接，两次普通一致性读，在任何行锁之前），话题直接取自同一次读到的 `posts` 行。拒绝时在事务内抛出，什么都不写。
RED `red-b3-082656`：5 次互动 15 次获取、被拒 1 次 2 次获取；GREEN `green-b3-*`（含 `VisibilityAndOfflineIT`，10 个类）。
压测：Hikari 获取/请求从 3.0 降到 1.0（a200：36,166 → 12,164，12,000 个请求）。

## 互动压测汇总（每档 60 s）

| 阶段 | a200 p99 | b100 达成/5xx/p99 | b200 达成 rps/5xx | c100 达成/5xx/p99 | c200 达成 rps/5xx | a200 获取次数 |
|---|---|---|---|---|---|---|
| 基线 beafbea | 622 ms | 85.6 / 33 / 7.6 s | 88.5 / 26 | 88.4 / 26 / 7.4 s | 88.4 / 18 | 36,003 |
| B1 | 833 ms | 100 / 0 / 79 ms | 148.6 / 0 | 100 / 0 / 92 ms | 149.7 / 0 | 36,061 |
| B2 | 143 ms | 100 / 0 / 62 ms | 164.8 / 0 | 100 / 0 / 62 ms | 157.2 / 0 | 36,166 |
| B3 | 128 ms | 100 / 0 / 251 ms | 165.7 / 0 | 100 / 0 / 323 ms | 148.2 / 0 | 12,164 |
| B2b | 206 ms | 100 / 0 / 67 ms | 166.5 / 0 | 100 / 0 / 144 ms | 180.4 / 0 | 12,164 |

完整表格见 `interactions/*-summary.md`。

**200 rps 的新上限和原因（B1 验收的“或给出新上限”）**：b/c 场景 4 个热帖，200 rps 时达成约 150–180 rps（k6 400 VU 用尽后丢弃迭代，**无错误**）。行锁等待采样 100% 在 `post_stats` PRIMARY（`locks/*.waits-by-object.txt`：B2 860 次、B2b 419 次，全部 `X,REC_NOT_GAP`）。每个热帖约 40–45 次/秒，即同一帖的点赞在它的计数行上串行。再往上需要分片计数或像 B1 一样异步汇总计数，属新决定，未做。

**100 rps 热帖档的波动**：B3 阶段 b100/c100 的 p99 升到 251/323 ms，看似退步。成对重跑（同一时段先 B2 jar 后 B3 jar，`locks/locks2_*`）：B2 的 c100 这次出现 11 个 5xx、p99 5.3 s，B3 的 c100 p99 110 ms。两个 jar 都既有好的也有坏的结果，说明 100 rps × 4 热帖正处在 `post_stats` 热行的临界点，结果双峰，不是 B3 的退步。

## B4 发送端空轮询退避

`IdleBackoffLoop`：连续空领取后停顿按 base、2·base…翻倍到上限；领到任务或收到新入队信号（bridge 本轮持久化了条目）时立即回到 base。只改“何时领取”，**租约和确认语义不变**（`CommerceDispatchBatchIT` 原有的租约、接管、旧 token 不能确认等用例全部通过）。配置 `content.sale.dispatch-idle-max-millis` 默认 8000，base = `content.sale.scan-delay-millis`；两者相等时就是原来的固定轮询。
- 本实例入队的任务不等退避（唤醒）；只有其他实例入队、到期重试、过期租约最多等上限。
- RED（`red-b4b5-093352` 单元、`red-b4b5-it-093508` IT）：临时把退避恢复成固定轮询（只改 `IdleBackoffLoop` 两行，跑完从备份还原，`cmp` 核对一致）。单元 6 例中 3 例失败；IT `batchIdleSenderBacksOffAndStillSendsUnsignalledWorkWithinTheCap` 失败：空闲 3 s 内空领取 29 次（要求 ≤8）。唤醒用例在固定 100 ms 轮询下也通过，它是守卫，不算 RED。GREEN 见 B5 一起的 `green-b4b5-*`。
- **本轮代码先于 RED 写成**，RED 是事后用上述临时回退补做的，如实记录。
- **其他也是 1 s 轮询的后台任务**（未改，报告给用户）：relay 扫描（`content.relay.scan-delay-millis`）、pipeline 扫描（worker tick、modes scan、trimmer tick），以及 B1 新增的聚合任务（空闲时每秒 1 个事务续租约）。
- 压测见 §B4/B5 压测。

## B5 抢购缓存同步

**逐类分析**（见 `SaleEntryRedis.APPLY` 与 `SaleIngress.sync`）：
- **成功结算后的 OWNER 任务：多余，删除。** REQUEST 任务的 APPLY 在 SUCCEEDED 时已写 `o:<user>:<post>=reservationId`（已是 GIVEAWAY 时保留），正是 OWNER 任务会算出的值。顺序不敏感的理由：每个账号/素材一生只有一张订单（`insertOrder` 唯一约束，退款后订单仍在，`orderByUserPost` 不按状态过滤），所以 owner 只会在 "" → reservationId ↔ GIVEAWAY 之间变化；REQUEST 只在订单已存在时写 reservationId，且从不覆盖 GIVEAWAY，迟到的 REQUEST 应用与之后任何 OWNER 状态一致。
- **拒绝（ALREADY_OWNED 以外）后的 OWNER 任务：多余，删除。** 拒绝不改变归属。
- **ALREADY_OWNED 拒绝后的 OWNER 任务：保留。** 准入脚本会先查 `o:`，能走到结算说明 Redis 缺 owner，这次 OWNER 同步是修复。
- **REQUEST 任务：必要。** Redis 里仍是 PROCESSING，`getRequest` 先读 Redis；拒绝时还要把 Redis 库存加回。改为批量。

**实现**：
- 直接调用 `settle()`（测试、人工结算）：本次结算弄脏的任务在**一个**事务里应用（`syncBatch`），返回前缓存已同步，语义不变。
- MQ 消费路径（`settleQueued`）：任务交给 `SaleCacheSyncBatcher`，间隔 `content.sale.cache-sync-batch-millis` 默认 **100 ms**（范围 1–1000），每批最多 200 个，一批一个事务。
- 扫描器 `syncJobs`：本轮所有脏任务一个事务（原来一个任务一个事务）。
- **版本检查保留**：每个任务在自己的行锁下（主键顺序加锁，并发批次不会互相死锁），已应用就跳过，MySQL `applied WHERE revision=` 加 Lua `v:` 版本，旧任务不会覆盖新状态（`b5BatchNeverOverwritesANewerCacheVersion`）。
- **一致性与恢复的影响**：只改时机。内存队列丢了（进程退出）或批次失败（Redis 断开）不丢数据：任务仍是 `material_sale_cache_jobs` 里的脏行，由扫描器或恢复流程应用。批次中途 Redis 失败则整批回滚，已写入 Redis 的部分是同一版本的幂等重放（拒绝时的库存加回由前一状态守护，退款由 `HSETNX` 守护）。

**测试**：RED `red-b4b5-it-093508`：成功结算 3 次提交（目标 2）、拒绝 3 次（目标 2）、5 单排队结算 15 次（目标 5）、扫描器找不到延迟的脏任务。提交按调用线程计数（JFR `jdbc.commit` 事件）。GREEN `green-b4b5-*`：`CommerceDispatchBatchIT`、`CommerceIngressRevisionIT`、`MaterialCommercePurchaseIT`、`MaterialCommerceRefundBlacklistIT`、`CommerceCampaignLockIT` 共 199 例通过；真实 broker `green-b5-realmq-*`：`CommerceRealMqIT` 41、`CommerceDispatchBatchRealMqIT` 64 例通过。

### B4/B5 压测
计划规定的 `run_dispatch_comparison.py` B2 档依赖开发库 `content`（要求 V26；新 jar 启动会把它迁到 V32，不可逆）、:29000 的 MinIO（当前没有）、旧 token 池和 HTTP 登录造数（被 A2 限流）。按已定的造数前置在独立库上改造它工作量大，本轮改为同口径的**进程内实测**：`CommerceSyncPerfIT`（选择性开启，不进 CI），真实 RocketMQ（Testcontainers），B2 档配置（批 20、并发 2、bridge 20、20 消费线程、扫描 50 ms），记录：
- 空闲提交/秒（B4）
- 60/s 和 120/s 各 60 s 的每单提交数（`Com_commit` 减去空闲速率）
- “请求返回 → 客户端看到 SUCCEEDED”时延，以及订单提交 → 客户端可见的部分
- 每秒结算数

改前用 `git archive beafbea` 导出的源码树（`target/src-beafbea`，不碰工作区和暂存区）跑同一个测试文件。运行方式：

```
CONTENT_REAL_MQ_IT=true mvn -o -Pintegration verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='CommerceSyncPerfIT#perfCacheSync' -DargLine="... -Dcontent.b5.perf=true -Dcontent.b5.out=<新目录>"
```

**结果**（`b4b5-sync-114642/`；同一台机器、同一测试文件（两棵树中逐字节相同）、同一参数和堆大小，先跑改前、后跑改后，各一次，没有重复）：

| | 改前 `beafbea` | 改后 B4+B5 |
|---|---|---|
| 空闲提交/秒（30 s，整库 `Com_commit`） | 17.4 | **1.1** |
| r60：每单提交（减空闲速率）/ 未减 | 3.78 / 4.15 | **2.48 / 2.50** |
| r120：每单提交（减空闲速率）/ 未减 | 3.74 / 4.15 | **2.37 / 2.39** |
| r60：结算速率 / 排空用时 | 46.2 单/秒 / 78.2 s | 50.0 单/秒 / 72.5 s |
| r120：结算速率 / 排空用时 | 43.2 单/秒 / 166.7 s | 49.4 单/秒 / 146.3 s |
| warm（20/s）：请求返回 → 看到 SUCCEEDED p50/p95/p99 | 318 / 481 / 561 ms | 246 / 428 / 485 ms |
| warm：订单提交 → 看到 p50/p95/p99 | 88 / 128 / 143 ms | 82 / 175 / 215 ms |
| r60：请求返回 → 看到 p50/p95/p99 | 0.8 / 52.7 / 65.8 s | 0.7 / 41.7 / 56.1 s |
| r60：订单提交 → 看到 p50/p95/p99 | 288 / 810 / 1007 ms | 313 / 584 / 743 ms |
| r120：请求返回 → 看到 p50/p95/p99 | 30.0 / 131.7 / 152.3 s | 42.5 / 113.9 / 131.8 s |
| r120：订单提交 → 看到 p50/p95/p99 | 1044 / 3522 / 4321 ms | 1008 / 3456 / 4251 ms |

两边每档的订单数相同（200/3600/7200），没有失败，也没有未结束的请求。

**解读**：
- **B4**：空闲时整库提交 17.4 → 1.1 次/秒，基本是空领取的消失。
- **B5**：负载下每单提交减少约 36%（未减空闲速率的口径同样从 4.15 降到 2.4–2.5），结算吞吐提高约 8–14%，排空时间缩短 7–12%。
- **两边在 60/s 和 120/s 都饱和**（结算 43–50 单/秒，低于下单速率），积压随时间增长。所以这两档的“请求返回 → 看到”主要是排队时间，只能看趋势，不代表单笔时延。r120 的 p50 改后更高（30.0 → 42.5 s），p95/p99 更低；每边只跑了一次，没有重复验证，原因未分析。
- **缓存同步部分**（订单提交 → 看到）：warm 档 p50 基本相同，p95/p99 改后高约 50–70 ms，与 100 ms 的批间隔一致；r60 档改后更低；r120 档持平。
- 改后一侧开始时，改前一侧的 3 个容器还在由 Ryuk 回收，持续时间不超过启动阶段。

**不能与 campaign-lock 系列报告直接比较**：那一系列用 `run_dispatch_comparison.py` 经 HTTP 驱动开发环境（compose 栈），这里是进程内调用、Testcontainers 的 MySQL/Redis/RocketMQ、未调优，绝对值只在这两列之间有意义。

---

## B6 关注潮（R6）与推模式扩散（R7）

`FanoutMeasureIT`（选择性开启，不进 CI；Testcontainers MySQL/Redis，未按 compose 实例调优，绝对值只代表这个环境）。共三次运行：
- `095608`：R6 4/16 线程完成后，系统内存告急，运行被 Claude Code 中止；R7 因夹具漏了 feed Redis 初始化而没有完成（证据 `b6-r6/`，保留）
- `111959`：R7 完成；R6 64 线程有 26 次关注失败，夹具当时断言全部成功，没有写出结果文件（证据 `b6-111959/`）
- `112855`：夹具改为记录失败类型、先写结果再检查之后重跑 R6，三档完整（证据 `b6-r6-112855/`，下表）

**R6 关注潮**（1 个作者 + 每档 1000 个新账号，作者每 100 ms 发一帖，连接池 16）：

| 线程 | 关注/秒 | 关注 p50 / p99 | 失败 | 发帖 p50：无关注潮 → 关注潮中 | 行锁等待次数 / 时间 | 等锁采样 |
|---|---|---|---|---|---|---|
| 4 | 25.7 | 144 / 320 ms | 0 | 26 → 151 ms | 1,191 / 141 s | 100% `author_feed_mode` PRIMARY |
| 16 | 29.7 | 517 / 793 ms | 0 | 17 → 527 ms | 1,058 / 495 s | 100% `author_feed_mode` PRIMARY |
| 64 | 30.9 | 2,062 / 5,166 ms | **7**（另一次运行 26） | 16 → 2,182 ms（窗口内只发出 7 帖） | 1,006 / 479 s | 100% `author_feed_mode` PRIMARY |

64 线程的失败全部是取连接超时：`CannotCreateTransactionException <- SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 5000ms (total=16, active=16, idle=0, waiting=49)`。16 个连接都被排在作者行上的关注占住，其余请求等不到连接。

**问题规模**：关注事务一开始就对作者行 `FOR UPDATE`（`FeedMapper.author`），持有到提交，期间复制历史帖、写 outbox。一个作者的关注**全局串行，约 25–31 次/秒，与线程数无关**；线程越多排队越长。排队还占住连接：64 并发时 0.7%–2.6% 的关注因连接池超时失败，同时作者发帖（同样要锁这一行）P50 超过 2 s。

**R7 推扩散**（`111959`，一个 PUSH 作者，粉丝用 SQL 预置，一个 worker 线程驱动）：

| 粉丝 | 发帖事务 | 发帖 → 任务创建 | 发帖 → 最后一个粉丝写入 | 粉丝/秒 | SQL 条数（每粉丝 / 每秒） | Redis 命令（每粉丝 / 每秒） |
|---|---|---|---|---|---|---|
| 1 万 | 20 ms | 41 ms | **9.9 s** | 1,010 | 11,478（1.15 / 1,159） | 50,001（5.0 / 5,048） |
| 10 万 | 21 ms | 36 ms | **95.2 s** | 1,050 | 114,078（1.14 / 1,198） | 500,001（5.0 / 5,250） |

第一个和最后一个粉丝都能在 feed 里读到这条帖子（夹具已核对）。`Questions` 与 Redis 计数是整个实例的，其他客户端只有夹具每批 1 条进度查询和 2 次 feed 读取。

**问题规模**：时延与粉丝数线性增长，约 1000 粉丝/秒，10 万粉丝要 95 s 才全部可见。一个帖子的 PUBLISH 任务只由一个持租约的 worker 按每批 100 个粉丝串行处理，增加 worker 线程没有帮助。每个粉丝约 1 条 SQL（`eligible` 逐个查询）和 5 条 Redis 命令（带代际栅栏的写入脚本）。

**建议**（已按决定记入计划 B7、B8，本轮不实现）：
- **B7（R6）**：把作者行锁缩到只为取 `publish_seq` 快照，或改为一致性读（需论证 `follow_cutoff_seq` 语义下不漏不重）；历史复制和 outbox 移出锁。先论证再实现。
- **B8（R7）**：每批 100 个粉丝的 `eligible` 合成一条 SQL；Redis 写用 pipeline 或一次脚本写一批；必要时把一个帖子的扩散按粉丝区间拆成多个可并行的任务。前两项不改变租约和栅栏语义。

---

## 已知遗留与说明
- 同一用户同帖点赞/点踩互相死锁（既有，见 B2；已记入计划 B10，P1）。
- B4 与 B2 之后的 jar 同时包含 B4 改动（只在抢购路径，不影响互动压测）。
- 第一次基线运行因阶段名带连字符生成非法库名，没有发起负载，日志保留（`base-beafbea.log`）；第一次 B2/B3 成对重跑因脚本修改错误没有发起负载，日志保留（`locks/locks_b*_100.log`）。
- 所有 RED/GREEN 目录都在 `target/hardening-b/`；完整 IT 由 CI 验证（推送后补记结果）。
- B4/B5 测量的第一次运行因测试继承了提交套件的 `content.sale.scan-enabled=false` 而没有派发，第二次被系统内存回收中止，日志都在 `target/hardening-b/b5-perf-113142`、`b5-perf-113818`（不进 docs）。
