# 第 B 轮报告（2026-11-22）

基线 `beafbea`。全部改动**未提交**（提交需用户明确要求；本轮一次提交尝试被拦下，未产生任何提交或暂存）。
环境：插电、高性能模式，同一时间只有一个会话跑测试或压测。互动压测用 `infra/perf/interactions`（独立库 `content_perf_r1_<phase>_<时刻>`，k6 开放模型，每档 60 s，账号直接写库、token 本地签发）。每个阶段用的 jar、`git diff`、`git status` 和未跟踪源文件副本在 `target/perf-hardening-b-20261122/app-<阶段>.*`（不进 docs）。

| 项 | 状态 | 一句话 |
|---|---|---|
| B1 话题分钟桶热点 | 完成（本地相关 IT 通过，待 CI） | 改为异步聚合；b/c 100 rps 错误从 33/26 降到 0；200 rps 新上限约 150–180 rps，原因是 `post_stats` 热行 |
| B2 计数增量 + 对账 | 完成（含 B2b 语句顺序） | 1M 赞的帖子单次点赞的计数部分 69.4 ms → 0.07 ms；顺带修掉两处既有错误（并发丢计数、重复举报被计数） |
| B3 每次点赞 1 个连接 | 完成 | Hikari 获取次数/请求 3.0 → 1.0 |
| B4 发送端空轮询退避 | 代码和测试完成；**压测缺证据** | 空闲 3 s 内空领取 29 → ≤8 次；有信号时立即恢复 |
| B5 抢购缓存同步 | 代码和测试完成；**压测缺证据** | 直接结算每单提交 3 → 2；MQ 消费路径的缓存同步按批 1 次提交 |
| B6 关注潮 / 推扩散 | **部分实测**：R6 4/16 线程有数；R6 64 线程、R7 缺证据 | 关注在作者行上串行，约 25 次/秒封顶；建议见 §B6，**等决定** |

缺证据的两项（B4/B5 抢购压测、B6 剩余部分）原因相同：B6 实测运行时系统内存告急，进程被 Claude Code 中止（不是测试本身的失败）。按要求未自行重跑，等用户确认后再跑，脚本已就绪（见各节）。

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
- **压测缺证据**，见 §B4/B5 压测。

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

### B4/B5 压测（缺证据，待用户确认后跑）
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

## B6 关注潮（R6）与推模式扩散（R7）

`FanoutMeasureIT`（选择性开启，不进 CI；Testcontainers MySQL 未按 compose 实例调优，绝对值只代表这个环境）。

**R6（实测，1 个作者 + 1000 个新账号，作者每 100 ms 发一帖）**：

| 线程 | 关注/秒 | 关注 p50 / p99 | 发帖 p50（无关注潮 → 关注潮中） | 行锁等待次数 / 时间 | 等锁采样 |
|---|---|---|---|---|---|
| 4 | 24.8 | 147 / 361 ms | 26 → 156 ms | 1,193 / 147 s | 100% `author_feed_mode` PRIMARY |
| 16 | 27.4 | 542 / 919 ms | 18 → 556 ms | 1,057 / 537 s | 100% `author_feed_mode` PRIMARY |
| 64 | 缺证据 | | | | |

**问题规模**：关注事务一开始就对作者行 `FOR UPDATE`（`FeedMapper.author`），并持有到提交，期间还复制历史帖（最多 `historyLimit` 条）、写 outbox。所以一个作者的关注**全局串行**，约 25 次/秒，与线程数无关；线程越多，排队越长。作者发帖也要锁这一行（`publish_seq`），关注潮中发帖时延随排队长度上升（16 线程时 p50 约 0.56 s）。外推到 64 线程或真实大 V 属推算，不写。

**R7（推扩散）缺证据**：首次运行的测试夹具漏了 feed Redis 初始化（`PerfHarnessIT` 在扩散前调用 `recovery.rebuild`），每批都被代际栅栏挡下（“Redis recovery generation changed; old writer fenced”），R7 没有完成。已修夹具，未重跑。从代码看规模：一个帖子的 PUBLISH 任务按每批 100 个粉丝串行处理，每个粉丝 1 次 `eligible` 查询加 1 次 Redis 写，即每个粉丝约 1 条 SQL 和 1 条 Redis 命令，10 万粉丝约 1000 批，只能由一个持有租约的 worker 执行。时延需实测。

**建议（等决定）**：
- **R6**：把作者行的锁从“关注全程”缩到“只为取 `publish_seq` 快照”，可选做法：(a) 改成普通一致性读（关注只需要截止序号，与发帖并发时用 `follow_cutoff_seq` 的语义保证不漏不重，需要论证）；(b) 历史复制和 outbox 移到锁外或异步。建议**本轮之后单列修复**，先论证 (a) 的正确性。
- **R7**：先补测（确认后跑修好的 `FanoutMeasureIT`），再定。可能的方向：`eligible` 批量化（每批 100 个粉丝一条 SQL）、Redis 写用 pipeline，均不改变租约/栅栏语义。

---

## 已知遗留与说明
- 同一用户同帖点赞/点踩互相死锁（既有，见 B2）。
- B4 与 B2 之后的 jar 同时包含 B4 改动（只在抢购路径，不影响互动压测）。
- 第一次基线运行因阶段名带连字符生成非法库名，没有发起负载，日志保留（`base-beafbea.log`）；第一次 B2/B3 成对重跑因脚本修改错误没有发起负载，日志保留（`locks/locks_b*_100.log`）。
- 所有 RED/GREEN 目录都在 `target/hardening-b/`；CI 还没有跑本轮改动（未提交）。
