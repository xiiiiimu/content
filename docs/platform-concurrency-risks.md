# 平台高并发写路径风险清单

> 2026-11-20 · 分支 `feat/v1.1-material-commerce` @ `8c88b75` · 本轮只做审查和小规模验证，未改业务代码。
> 原始证据（SQL、脚本、输出）：`target/risk-probe-20261120-215924/`（未纳入 git）。

## 0. 结论速览

| # | 风险 | 影响 | 可能性 | 关键证据 |
|---|------|------|--------|----------|
| R1 | 未带 topic 的点赞/评论都落到 `general`，全站串行在同一行分钟桶上 | 高 | 高 | 帖子详情页不传 topic；同话题 4 并发：吞吐 145→87 tx/s，800 个事务中 798 个等锁，平均等 24 ms |
| R2 | `bucketsSince` 每次全表扫描所有历史桶，且执行时持有话题锁；桶表从不清理 | 高 | 高（随时间必然出现） | 历史桶 144 万行时，单次点赞中 `bucketsSince` 平均 136.6 ms，事务平均 145 ms |
| R3 | `reconcile` 每次跑 4 个 COUNT(*)，同一帖子的互动串行在 `post_stats` 行 | 中高 | 中（热门帖） | 已有 100k 赞时 reconcile 6.7 ms，1M 赞时 65.6 ms；同帖 4 并发平均等锁 24.9 ms |
| R4 | 互动接口无限流；举报不去重；topic 由客户端任意指定 | 中高 | 中 | 全仓只有 `SaleDispatcher` 有并发闸门；`InteractionController.java:14` 直接透传 topic |
| R5 | 登录 BCrypt 校验在请求线程同步执行，无限流 | 中 | 中 | 每次 61.7 ms CPU（单核约 16 次/秒） |
| R6 | 关注/取关对被关注作者行加 `FOR UPDATE`，大 V 的关注潮串行 | 中 | 中 | `FeedMapper.java:18`，`RelationshipService.java:20-30`（未实测） |
| R7 | 推模式扩散每个粉丝 1 次 SQL + 1 次 Redis，总量随粉丝数线性增长 | 中 | 低～中（取决于有多少作者是 PUSH） | `DistributionWorker.java:25-38`（未实测） |
| R8 | 反复点赞/取消可以把话题热度刷成负数 | 中（数据正确性） | 中 | `InteractionService.java:29,37` 事件 ID 不对称 |
| R9 | 刷新阈值默认 3，几乎每次互动都会尝试插入风险事件 | 低 | 高 | `FeedConfiguration.java:72` |
| R10 | 评论幂等是“先查后插”，并发重复请求会报 500；通知写入风险低 | 低 | 低 | `InteractionService.java:73-92` |

**对你三个假设的结论：**

1. **reconcile 每次都用 COUNT(*) 全量重算？是。** 点赞、取消赞、踩、取消踩、举报、评论都会调 `reconcile(post)`（`InteractionService.java:127-130`）。它按 `post_id` 前缀分别对 `post_likes`、`post_comments`、`post_dislikes`、`post_reports` 做 4 次范围扫描，然后整体覆盖写 `post_stats`。即使 `INSERT IGNORE` 实际没插入（重复点赞），也照样重算。
2. **`trend_topic_serial FOR UPDATE` 让同一话题的互动串行？是，但它不是第一个串行点。** 实测（B2）的等待几乎都发生在前一步：`topic_minute_buckets(topic, 分钟)` 行的 upsert（平均 24.26 ms）。走到 serial 锁时已经排好队了，所以只等 0.12 ms。分钟桶行在同一分钟内串行，serial 行跨分钟也串行。**只去掉 serial 锁，同一分钟内仍然串行**，两处都要动。
3. **`bucketsSince` 每次扫描最近一小时所有话题？比这更糟。** 主键是 `(topic, bucket_start_ms)`，没有以 `bucket_start_ms` 开头的索引。EXPLAIN 显示 `type=index, key=PRIMARY, rows=1,296,792`，也就是**全表的所有历史桶**都会被扫描，不只最近一小时。全仓没有任何地方定期清理旧桶，只有手动 `rebuild` 会整表 `DELETE`。最近一小时内所有话题的行还会被整体拉回 Java，再按 topic 过滤（`TrendingService.java:32`）。

---

## 1. 各写路径审查

通用前提：`feedTransactions` 的隔离级别是 READ COMMITTED（没有间隙锁），超时 15 s（`FeedConfiguration.java:49`）。`TrendingService.record` 内的 `tx.executeWithoutResult` 是 PROPAGATION_REQUIRED，会**加入外层互动事务**，所以话题相关的锁都要持有到外层提交。互动相关表（`post_likes`、`post_dislikes`、`post_reports`、`post_comments`、`post_stats`、`topic_*`）**都没有外键**，因此没有外键共享锁。

### 1.1 点赞 / 取消赞 / 踩 / 取消踩
- **入口**：`InteractionController.java:14-17` → `InteractionService.like/unlike/dislike/undislike`（`:23-56`）
- **事务外**：`visibility.read`
- **事务内 SQL**（以 like 为例，按执行顺序）：
  1. `DELETE post_dislikes WHERE post_id,user_id`
  2. `INSERT IGNORE post_likes`
  3. reconcile：4 次 `SELECT COUNT(*) … WHERE post_id=?`，然后 `INSERT … ON DUPLICATE KEY UPDATE post_stats`，再 `SELECT post_stats`
  4. 仅当真的插入了新行：
     - `INSERT IGNORE topic_event_ledger`
     - `INSERT … ON DUP UPDATE topic_minute_buckets`（`net=net+1`）
     - `INSERT IGNORE trend_topic_serial`
     - `SELECT … trend_topic_serial FOR UPDATE`
     - `SELECT … topic_minute_buckets WHERE bucket_start_ms>=now-1h ORDER BY topic,bucket_start_ms`（全表索引扫描）
     - 一小时净值 ≥3 时 `INSERT trend_risk_events`（重复键异常被吞掉）
  5. `SELECT post_stats`
- **行锁**：
  - `post_likes(post,user)` 和 `post_dislikes(post,user)`：只锁该用户自己的行，不冲突
  - **`post_stats(post)` X 锁**：同一帖子的互动都要排队
  - **`topic_minute_buckets(topic, 分钟)` X 锁**：同一话题、同一分钟的互动都要排队
  - **`trend_topic_serial(topic)` FOR UPDATE**：同一话题的互动都要排队
  - 以上三把锁都持有到提交。加锁顺序固定为 stats → bucket → serial，审查中没有发现顺序反转导致死锁的路径。
- **热点行**：同一帖子 → `post_stats`；同一话题 → 分钟桶和 serial；**没带 topic 的请求都算到 `general`**（R1）。
- **限流**：无。
- 取消赞的话题事件 ID 带时间戳，点赞的不带，见 R8。踩、取消踩不触发话题记录。

### 1.2 评论
- **入口**：`InteractionService.comment`（`:69-105`）
- **事务内 SQL**：
  - `SELECT existingComment(user,key)`
  - 回复时加 `SELECT commentById`
  - 带素材版本时加素材版本校验
  - `INSERT post_comments`，再 `SELECT existingComment`
  - reconcile（4×COUNT + upsert）
  - trending.record（同 1.1，而且**不论是否新插入，都会记一次话题事件**）
  - `RelationshipService.emit`：`INSERT outbox_events` + `UPDATE payload`
  - 可选的 assistantEnqueue
- **锁**：
  - `post_comments` 唯一键 `(user_id, idempotency_key)`
  - `post_stats`、分钟桶、serial 三把热点锁同 1.1，而且锁持有期间还要写 outbox 和投递 assistant，持有时间比点赞更长
  - 引用 `post_comments` 的外键（`fk_assistant_comment` 等）在 assistant 子表插入时会对评论行加 S 锁，属于该评论自己的行，不构成热点
- **限流**：无。

### 1.3 举报
- **入口**：`InteractionService.report`（`:57-66`）
- **SQL**：`INSERT post_reports`（**没有唯一约束，同一用户可以无限次举报**），然后 reconcile。
- **锁**：`post_stats(post)` X 锁。
- **限流**：无。
- 返回的 `reportId` 固定为 0，是功能问题，不影响并发。

### 1.4 关注 / 取关
- **入口**：`RelationshipService.change`（`:17-32`）
- **SQL 和锁（按顺序）**：
  1. `feed_control id=1 FOR SHARE`：全局单行 S 锁
  2. `author_feed_mode(followee) FOR UPDATE`：**被关注作者行加 X 锁**
  3. `INSERT IGNORE follow_graph_version`
  4. `follow_graph_version(reader) FOR UPDATE`
  5. `follows(reader,author) FOR UPDATE`
  6. 关注时：`INSERT … ON DUP follows`；`SELECT posts history LIMIT 20`；最多 20 次 `INSERT follow_history`（逐条插入）
  7. `UPDATE follow_graph_version`
  8. `INSERT outbox_events` + `UPDATE payload`
- **热点**：同一被关注作者。关注、取关和该作者的发布（`PublishMapper.lockAuthor`）争用同一行 `author_feed_mode`。登录时的 `INSERT IGNORE author_feed_mode`（`UserMapper.java:16`）遇到已有行，要先拿 S 锁，作者行被锁时也会等。
- **限流**：无。

### 1.5 发帖与扩散（推 / 拉 / 混合）
- **发布事务**：`PublishTransaction.publish`（`:23-61`，READ COMMITTED）
  - `feed_control FOR SHARE` → `author_feed_mode FOR UPDATE` → `posts FOR UPDATE` → `publish_requests FOR UPDATE`
  - `UPDATE author_feed_mode` 递增 seq → `UPDATE posts` → `INSERT outbox` → `UPDATE outbox` → `INSERT publish_requests`
  - 外键：`posts.author_id` 和 `publish_requests` 都引用 `author_feed_mode`。作者行此时已被本事务 X 锁持有，外键检查不额外阻塞。
  - **热点只在同一作者**；没有 COUNT。
- **推模式**：消息经 MQ 到 `DistributionInbox.accept`，在一个事务内写 `consumer_inbox` 和 `feed_delivery_tasks`。之后由 `DistributionWorker.run` 处理（`:19-42`）：
  - 每批取 100 个粉丝，**每个粉丝单独跑一次 `eligible` 的 COUNT 查询**（N+1）
  - **每个粉丝单独发一次 Redis `addFenced`**，并且每次都 `new RedisFeedCandidateStore`
  - 一次 `tick` 最多串行处理 10 个任务
  - 认领任务时，`claim` 内有 `MAX(follower_id)` 子查询
- **拉模式**：发布时不扩散。读时 `FeedMapper.candidates` 用 follows×posts 的 JOIN，读放大随关注数增长，是读路径，本轮不展开。
- **模式切换**：`FeedModes` 由运维手动切换（`ModeMapper.change`），审查中没有发现按粉丝数自动切换的逻辑。
- **限流**：无。

### 1.6 通知写入
- 评论事件经 outbox、MQ 进入 inbox 后，由 `NotificationService.acceptCommentEvent` 执行，异步，不在评论请求的事务里。
- 每条评论最多 1 次 `INSERT IGNORE notifications`，唯一键 `(source_event_id, recipient_user_id)`。
- inbox 事务带一次 `feed_control FOR SHARE`。
- 点赞不生成通知。**风险低**。

### 1.7 登录
- **入口**：`AuthService.login`（`:28-35`），**不在事务里**。
- **SQL**：`SELECT users WHERE username`（唯一索引），然后 BCrypt 校验（strength 10），再 `INSERT IGNORE author_feed_mode`（自动提交），最后签发 token。
- **限流**：无。
- 注册在事务里先查后插；用户名唯一键兜底，并发重名会报 500 而不是 409（低风险）。

---

## 2. 实测

**方法**：
- 新建独立库 `risk_probe_20261120_215924`，用 `CREATE TABLE LIKE content.*` 复制表结构，不复制数据，不写 `content` 库，不碰抢购数据。帖子和用户 ID 都是合成的。
- 存储过程 `like_tx` 逐条复刻 1.1 的点赞 SQL 序列（READ COMMITTED，刷新阈值 3），用服务端 `SYSDATE(6)` 分段计时。
- 环境：MySQL 8.4.13（Docker Desktop）；`innodb_buffer_pool_size` 为 128 MB；空闲状态下单次提交约 6.5–7 ms（fsync）。
- **计时不包含** JDBC 往返、Java 处理、`visibility.read` 和 HTTP。应用里锁的持有时间只会比这里**更长**；端到端数字**缺证据**。

### A. 单次点赞事务耗时与已有赞数（顺序执行，每组 100 次，预热 5 次）

| 已有赞数 | 平均 | p50 | p95 | 最大 | 其中 reconcile 平均 |
|---------:|-----:|----:|----:|-----:|--------------------:|
| 0 | 7.30 ms | 7.03 | 10.55 | 17.84 | 0.15 ms |
| 1k | 7.02 | 6.97 | 9.42 | 15.24 | 0.20 |
| 10k | 7.82 | 7.42 | 10.33 | 17.26 | 0.81 |
| 100k | 13.82 | 13.56 | 16.97 | 18.99 | **6.68** |
| 1M | **73.35** | 71.43 | 83.77 | 101.67 | **65.62** |

- reconcile 耗时与已有赞数基本呈线性，约 0.066 ms / 千赞。
- 10k 以下时提交耗时（约 7 ms）占主导；100k 时 COUNT 已经和提交耗时相当；1M 时 COUNT 占约 90%。
- 评论、举报、踩都走同一个 reconcile，所以在百万赞的帖子上评论也要多付约 66 ms。

### B. 4 个会话并发，每个会话 200 次点赞，共 800 个事务

| 场景 | 墙钟 | 吞吐 | 平均 | p95 | 主要等待段 | 行锁等待次数 |
|------|-----:|-----:|-----:|----:|------------|---------------:|
| B1 4 帖 / 4 个不同话题 | 5.52 s | 145 tx/s | 12.99 ms | 18.37 | 无 | 0 |
| B2 4 帖 / **同一话题** | 9.19 s | **87 tx/s** | 35.38 | 43.99 | 分钟桶 upsert 24.26 ms；serial 锁 0.12 ms | 798 |
| B3 **同一帖子** / 同一话题 | 9.27 s | 86 tx/s | 35.79 | 44.22 | reconcile 中的 `post_stats` upsert 24.85 ms | 798 |
| B4 4 帖 / `general` | 8.94 s | 89 tx/s | 34.36 | 42.59 | 分钟桶 upsert 23.62 ms | 796 |

- 同一话题时，几乎每个事务都要等锁（798/800），吞吐降 40%，平均延迟约 2.7 倍。等待集中在**分钟桶行**，不在 serial 行。
- B3 结束后 `post_stats.like_count = 800 = COUNT(*)`：因为每次都全量重算，最终值是一致的。并发过程中计数是否会暂时偏少，没有测，**缺证据**。
- 行锁等待次数来自全局 `Innodb_row_lock_waits` 的前后差值，测试期间没有其他负载在跑。

### C. `bucketsSince` 与历史桶规模

- 写入 1000 个话题 × 1440 分钟（1–25 小时前）的历史桶，共 144 万行，全部在 1 小时窗口之外。
- EXPLAIN 前后都是 `type=index, key=PRIMARY`，`rows` 从 13 增长到 1,296,792。

| 历史桶行数 | 单次点赞平均 | p95 | 其中 bucketsSince |
|-----------:|---------:|----:|------------------:|
| 约 10 | 7.30 ms | 10.55 | 0.03 ms |
| 144 万 | **145.32 ms** | 161.09 | **136.61 ms** |

- 这 136.6 ms 都发生在拿到分钟桶 X 锁和 serial 锁**之后**、提交之前，所以同一话题每个事务至少持锁约 137 ms。由此推导，同一话题的串行吞吐上限 **≤ 约 7 tx/s**。这只是从实测持锁时间推导的，**没有经过并发实测验证**（见下方缺证据项）。

### D. 登录 BCrypt
- `BCryptPasswordEncoder` strength 10，单次 `matches` 平均 **61.68 ms**，单核约 16.2 次/秒，本机 16 核。
- 用的是本地 m2 里的 spring-security-crypto 5.7.8。BCrypt 计算量与库版本无关。

### 缺证据（本轮未测）
- C2/C3：有 144 万行历史时，同一话题 4 并发的实际吞吐；1000 个活跃话题时 `bucketsSince` 返回约 6 万行到 Java 的传输成本。脚本已写好，但执行被自动模式拒绝，未运行。
- 所有路径经 HTTP 和应用的端到端数字（含 JDBC 往返、Hikari 连接池默认 10 个连接下的排队）。
- 关注、推模式扩散、登录的并发实测。

---

## 3. 风险清单（按 影响 × 可能性 排序）

### R1 `general` 默认话题让全站点赞/评论串行
- **证据**：
  - `frontend/pages/posts/[id].vue:139,158`：帖子详情页点赞、取消赞不传 topic；`:108` 评论也不传
  - `InteractionService.java:29,37,94`：`topic==null?"general":topic`
  - 实测 B4 与 B2 结果一致：吞吐 89 tx/s，平均等锁 23.6 ms，800 个事务中 796 个等锁
- **预期出问题的规模**：同一分钟内，所有不带 topic 的点赞/评论共用一行分钟桶。按本机提交耗时，全站上限约百级 tx/s。和 R2 叠加后会降到个位数 tx/s（推导值，未实测）。等待的请求同时占着 Hikari 连接（默认 10 个），会连带拖慢其他接口。
- **建议测试**：起应用，N 个账号在 N 个**不同帖子**上通过 `/api/posts/{id}/likes`（不带 topic）以 50/100/200 rps 加压。记录 p99、`Innodb_row_lock_waits` 和 Hikari pending 指标。对照组：每个帖子带不同 topic。
- **修复方向**：
  - 没有话题的互动不进入话题统计；或者话题从帖子自身属性取，不信任客户端传入
  - 话题计数改为异步聚合：事务内只写 ledger 或 outbox，后台按分钟批量合并到桶
  - 或者把分钟桶拆成多个分片（`topic, bucket, shard`），读时求和

### R2 `bucketsSince` 全表扫描，执行时持有话题锁，且桶表无 TTL
- **证据**：
  - `InteractionMapper.java:54`（查询没有可用索引）、`TrendingService.java:30-32`（在 `lockTopicForEmit` 之后调用）、`V7__interactions_trending_media.sql`（主键 `(topic, bucket_start_ms)`）
  - EXPLAIN：`rows=1,296,792, type=index`
  - 实测 C：144 万行时单次点赞的扫描部分 136.6 ms
  - `GET /api/topics/trending`（`TrendingService.window`）用同一个查询，24 小时窗口也要扫全表
- **预期出问题的规模**：
  - 桶表行数 ≈ 活跃话题数 × 1440 / 天，而且只增不减
  - 实测：1000 个活跃话题累积一天（144 万行）时，单次互动已超过 140 ms
  - 30 天后约 4300 万行，单次扫描远超 buffer pool。具体数字没有测，**缺证据**，不做推算
  - 加上客户端可以任意指定 topic（R4），桶表行数可以被人为放大
- **建议测试**：
  - 补跑 C2：历史桶 0、14 万、144 万行三档 × 同一话题 4 并发，记录吞吐
  - 补跑 C3：1000 个话题都有最近一小时数据时，应用层 `bucketsSince` 的耗时
  - 都做在独立库里
- **修复方向**：
  - 改成只查当前话题：`WHERE topic=? AND bucket_start_ms>=?`，直接走主键前缀
  - 或者为 `window()` 加 `(bucket_start_ms, topic)` 索引
  - 刷新判断移出加锁区，或改用 Redis/内存中的滑动计数
  - 桶表和 ledger 加定期清理或按时间分区

### R3 reconcile 每次 4×COUNT(*) 全量重算，同一帖子在 `post_stats` 行串行
- **证据**：`InteractionService.java:127-130`；实测 A（100k 赞时 6.68 ms，1M 赞时 65.62 ms）；实测 B3（同帖 4 并发吞吐 86 tx/s，等待都在 `post_stats` upsert）。
- **预期出问题的规模**：
  - 单帖赞数到 10 万级时，每次互动多出约 7 ms 的 DB CPU；百万级时约 66 ms
  - 热门帖同时承受高频点赞时，DB CPU 线性上升。COUNT 在拿到 stats 锁之前执行，不延长锁持有时间，但会占住连接
  - 同一帖子的吞吐上限 ≈ 1 / (stats upsert 到提交的时间)
- **建议测试**：
  - 在独立库里预置 1M 赞，对单帖做 4/16/64 并发点赞，记录 DB CPU、p99，以及事务结束前 `post_stats` 与 COUNT 的偏差
  - 对照组：同样的赞数下改测评论接口
- **修复方向**：
  - 计数改为增量更新：根据 `INSERT IGNORE`/`DELETE` 的 affected rows 做 `like_count=like_count±1`
  - 全量 COUNT 只放在离线对账任务里
  - 极热帖再进一步：计数分片（`post_stats_shard`），或经 Redis 计数后异步落库

### R4 互动接口无限流、举报不去重、topic 由客户端控制
- **证据**：
  - 全仓只有 `SaleDispatcher` 有并发闸门（grep `RateLimiter|Semaphore|tryAcquire`）
  - `post_reports` 只有普通索引，没有唯一约束（`V14__material_posts.sql`）
  - `InteractionController.java:14` 的 `@RequestParam topic` 原样传入，没有和帖子校验
- **后果**：
  - 单个账号就能刷举报（每次触发 4×COUNT）
  - 可以凭空制造任意话题，给 R2 的桶表加速膨胀，也能操纵热榜
  - topic 超过 128 字符时，ledger 列是 `VARCHAR(128)`，严格模式下大概率报 500 而不是 400（**未验证**）
- **预期出问题的规模**：一个脚本账号就够，与平台规模无关。
- **建议测试**：单账号以 100 rps 刷举报和随机 topic 点赞 5 分钟，观察 `post_reports`、`topic_minute_buckets` 的增长和其他接口的 p99。
- **修复方向**：
  - 按用户和 IP 做令牌桶限流（Redis）
  - 举报加 `(post_id, reporter_user_id, reason)` 唯一约束，或设冷却时间
  - topic 由服务端从帖子取，或做白名单校验

### R5 登录 BCrypt 同步计算、无限流
- **证据**：`AuthService.java:31`；实测 61.68 ms/次（单核约 16 次/秒）。
- **预期出问题的规模**：16 核机器约 260 次/秒就会打满 CPU（16 × 16.2 推导，未实测）。撞库或登录高峰时，同进程的所有接口都会受影响。
- **建议测试**：起应用压 `/api/auth/login`（错误密码和正确密码各一组），在 50/100/300 rps 下记录 CPU 和其他接口的 p99。
- **修复方向**：
  - 按账号和 IP 限流，失败次数多时退避
  - BCrypt 放到独立的有界线程池，满了直接 429
  - `ensureAuthor` 改为只在注册时执行

### R6 关注/取关对被关注作者行加 `FOR UPDATE`
- **证据**：
  - `FeedMapper.java:18`（`author_feed_mode … FOR UPDATE`），`RelationshipService.java:20-30`
  - 事务内还有最多 20 次逐条 `INSERT follow_history` 和 outbox 写入，同样在持锁期间执行
  - 同一行还被发布（`PublishMapper.java:11`）和登录的 `INSERT IGNORE` 争用
- **预期出问题的规模**：大 V 开播或上热榜时出现的关注潮，同一作者每秒数十到上百次关注就会排队。没有实测，**缺证据**。
- **建议测试**：独立库里一个作者 + 1000 个账号并发关注，在 4/16/64 并发下记录吞吐和等锁时间；同时让该作者发帖，观察发布延迟。
- **修复方向**：
  - 关注不需要锁作者行，只需读一致的 `publish_seq`：可改用快照读，cutoff 允许轻微滞后，由历史回填兜底
  - `follow_history` 改批量插入，或移到异步 BACKFILL 任务（任务已存在）

### R7 推模式扩散逐粉丝 SQL + Redis
- **证据**：`DistributionWorker.java:25-26`（每个粉丝一次 `eligible` COUNT）、`:38`（每条写入单独发一次 Redis，并新建 store）、`:12`（每次 tick 串行处理 ≤10 个任务）。
- **预期出问题的规模**：
  - 粉丝 N 个的 PUSH 作者每发一帖，就有 N 次 SQL 加 N 次 Redis 往返；百万粉丝时扩散延迟以分钟计（推导，未实测）
  - 目前只有手动切换到 PUSH 的作者才会触发
- **建议测试**：独立库里一个 PUSH 作者分别有 1 万和 10 万粉丝，测发帖到最后一个粉丝 feed 可见的时延，以及 DB/Redis QPS。
- **修复方向**：
  - `eligible` 合并进 `followers` 查询，只查 `follow_cutoff_seq<?`
  - Redis 写用 pipeline，或一次 Lua 写多条
  - 大 V 强制走 PULL（混合模式按粉丝数阈值），worker 并行处理

### R8 点赞/取消赞的话题事件不对称，净值可被刷低
- **证据**：
  - 点赞事件 ID 是 `like:post:user`（固定），取消赞是 `unlike:post:user:<ms>`（每次都不同）（`InteractionService.java:29,37`）
  - 第二次点赞时 ledger 的 `INSERT IGNORE` 返回 0，不加分；每次取消赞都减 1
  - 所以“赞→取消→赞→取消”循环会让话题净值持续下降，ledger 也会不断变大
- **预期出问题的规模**：与规模无关，属于正确性和恶意刷榜问题。
- **建议测试**：单元测试或集成测试，同一用户循环 10 次赞/取消，断言话题净值 ≥0。
- **修复方向**：事件 ID 改用（帖子, 用户, 状态版本）；或者话题计数只在状态真正翻转时 ±1。

### R9 刷新阈值默认 3
- **证据**：`FeedConfiguration.java:72` 默认 `spike-threshold:3`。超过阈值后，每次互动都会做一次 `trend_risk_events` 插入并触发重复键错误（被吞掉），而且发生在加锁区内。
- **影响**：每次多一条语句和一次唯一索引 S 锁，单独影响很小，但会叠加在 R1/R2 上。
- **修复方向**：在内存或 Redis 里先判断本窗口是否已经报过，命中就跳过 DB 插入。生产阈值应显式配置。

### R10 评论幂等的竞态；通知写入
- **证据**：`InteractionService.java:73,92`，先 `existingComment` 再 `INSERT`。同一个 key 并发到达时，后到的会撞唯一键，`DuplicateKeyException` 没有被捕获，返回 500 而不是重放结果。
- **通知**：异步执行，单行 `INSERT IGNORE`，有唯一去重键，**风险低**。
- **修复方向**：捕获唯一键冲突后，在新事务里重读并返回重放结果（参照 `PublishService` 的做法）。

---

## 4. 建议的下一步（供决策）
- **修复顺序建议**：R2 的查询改写（只查当前话题）和 R1 的 `general` 处理改动最小、收益最大，并且互相叠加，建议放在同一轮。之后是 R3 的计数改增量更新，再之后是 R4/R5 的限流。
- **测试顺序建议**：先补 C2/C3（并发 + 历史桶，属于缺证据项）。然后做 R1 的应用级端到端压测，拿到含 JDBC 往返和连接池排队的真实上限。
- **清理**：实测库 `risk_probe_20261120_215924` 保留作证据，是否删除（`DROP DATABASE risk_probe_20261120_215924;`）由你决定。
