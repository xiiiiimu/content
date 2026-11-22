# 活动行热点锁分析与方案对比（2026-11-19）

状态：**仅分析，未实现**。等待确认方案后再按 RED→GREEN 推进。

背景：MQ 批量发送对照（`docs/perf-evidence/commerce/mq-dispatch-batch-20261119.md`）中，B/C 档发送侧已不再积压，但全程提交 TPS 停在约 10/s，积压转移到 Redis Stream（bridge 落库前）。本文确认原因，并评估三个方案。

## 1. 冲突链

### 1.1 消费者落单事务（`SaleService.settleBody`，`feedTransactions`，READ COMMITTED）

| 顺序 | 语句 | 锁 |
|---|---|---|
| 1 | `ingress.guard()` → `material_sale_runtime … FOR SHARE` | 运行时行 S |
| 2 | `lockRequest` → `material_purchase_requests … FOR UPDATE` | 请求行 X |
| 3 | `touchLock` + `lockDecision` | 买家/素材决策行 X |
| 4 | **`lockCampaign` → `material_sale_campaigns WHERE id=? FOR UPDATE`** | **活动行 X，持有到提交** |
| 5 | `activeBlacklist`、`FeedMapper.post`（非锁定读） | — |
| 6 | `tryIncrementSold`（已是条件 UPDATE：`state='OPEN' AND sold_count<stock_cap`） | 活动行 X（已持有） |
| 7 | `insertOrder`（`fk_order_campaign`、`fk_order_reservation`）、`insertEntitlement`、`finishRequest`、`insertReceipt`、`dirty`×2 | 外键检查对活动行要 S，已被本事务 X 覆盖 |
| 8 | `jdbc.commit` | 释放 |

### 1.2 bridge 落库事务（`SaleBridgeBatch.persist`）

`campaigns(ids)` 为非锁定读；`ensureFences` → `insertRequests`（多行 INSERT）→ `enqueue` → 提交。**唯一触及活动行的是 `insertRequests` 的 `fk_purchase_campaign` 外键检查：对活动行加 `S,REC_NOT_GAP`，持有到 bridge 提交。**

### 1.3 小规模复现（performance_schema 直接抓到持锁者）

真实表、两个 READ COMMITTED 会话（与应用一致），所有事务均 ROLLBACK，事后核对残留行 0。证据：`target/lock-analysis-20261119-225722/lock-repro.json`、`run2/lock-repro.json`（私有）。

| 场景 | 持锁者（GRANTED） | 等待者（WAITING） |
|---|---|---|
| 1. 落单先锁 | 连接 21643：`material_sale_campaigns` PRIMARY `X,REC_NOT_GAP` 行 127 | 连接 21644：bridge 式 `INSERT material_purchase_requests`，等待同一行 `S,REC_NOT_GAP`；持锁者回滚后约 4.3 s 才完成插入 |
| 2. bridge 先插 | 连接 21648：未提交插入持有活动行 127 `S,REC_NOT_GAP` | 连接 21649：`lockCampaign … FOR UPDATE` 等待 `X,REC_NOT_GAP`，持有者回滚后才获得 |
| 3. 插入订单 | 连接 21670：同一事务插入请求和订单后，活动行 127 上持有 `S,REC_NOT_GAP` | — |

`data_lock_waits` 直接给出等待者与阻塞者的连接号、锁模式和锁数据（行 127）。场景 3 的请求与订单在同一事务中插入，无法单独区分是哪一个外键加的 S；InnoDB 两个外键的检查行为相同，场景 2 已单独证明请求插入这一侧。

### 1.4 负载中的量化（B/C 档 JFR）

| | A | B | C |
|---|---|---|---|
| 活动行 X 持有（`lockCampaign` 返回 → 首次提交结束）均值 / P50 / P95 (ms) | 96.1 / 79 / 211 | 99.5 / 83 / 207 | 96.7 / 81 / 198 |
| 其中 `tryIncrementSold` 开始 → 提交 均值 (ms) | 87.7 | 90.9 | 88.0 |
| 其中提交本身 均值 (ms) | 31.9 | 35.5 | 32.9 |
| `lockCampaign` 等待 P50 (ms) | 3 | 1546 | 1410 |
| bridge `insertRequests` P50 (ms) | 31 | 1552 | 1483 |
| 全程提交 TPS | 6.25 | 10.02 | 10.33 |

持有区间内各语句均值约 4–8 ms，两次 `dirty` 合计约 15 ms，提交约 33 ms。同一活动的落单在这一行上串行，**上限 ≈ 1000 / 98 ≈ 10.2/s**，与 B/C 实测全程 TPS 10.0–10.3/s 吻合。bridge 的 S 请求排在落单的 X 队列后面；bridge 持有 S 期间又挡住后续落单。A 档落单速率受串行发送限制（约 6/s），没到这个上限，所以没有出现排队。

## 2. 三个方案

### (a) 活动行库存更新移到落单事务最后，改为条件 UPDATE

- 做法：去掉 `lockCampaign … FOR UPDATE`；活动按非锁定读读取（`start_ms`、`end_ms`、`frozen_version_id`、价格等只在创建时写入，可变的只有 `state` 和 `sold_count`）。先完成各项检查和插入，最后一条语句为：

  `UPDATE material_sale_campaigns SET sold_count=sold_count+1,updated_at_ms=? WHERE id=? AND state='OPEN' AND start_ms<=? AND end_ms>=? AND sold_count<stock_cap`

  影响 0 行时整个事务回滚，在新事务里重读活动、判定原因（CLOSED / NOT_STARTED / ENDED / SOLD_OUT）并 reject。原 `DuplicateKeyException` 后的 `tryDecrementSold` 补偿不再需要。
- **前提：必须同时去掉 `fk_order_campaign`。** 订单插入会对活动行加 S（复现场景 3）；若 S 在前、条件 UPDATE 的 X 在后，两个并发落单各持 S、都要升级为 X，会形成死锁。若保留该外键，UPDATE 只能放在订单插入之前，持有时间约等于现在的"`tryIncrementSold` → 提交"（约 88–91 ms），收益约 +8%。
- 预期收益（去掉 `fk_order_campaign` 时）：X 持有 ≈ UPDATE（约 8 ms）+ 提交（约 33 ms）≈ 41 ms，上限约 24/s（约 2.4 倍）。**这是按阶段耗时推算的估计值，未实测。**提交本身（约 33 ms，Docker Desktop 下的 fsync）是该方案的下限。
- 若不同时做 (b)，bridge 的 S 仍会和这段较短的 X 互相排队，但等待时间会按持有时间同比缩短。

### (b) 去掉 `fk_purchase_campaign`，由应用层保证引用正确

- 做法：新增 Flyway 迁移删除该外键，保留 `campaign_id` 上的索引；不改已执行的迁移。
- 引用保证：
  - 代码中没有删除活动的语句（`src/main` 中无 `DELETE FROM material_sale_campaigns`）。
  - bridge 落库前已按 ID 查询活动，并校验 post、merchant、价格。活动不存在时记为 `CAMPAIGN_SNAPSHOT_MISMATCH` 并隔离。
  - 剩余风险是人工删库产生孤儿行。在对账中加入孤儿引用检查。
- 预期收益：bridge `insertRequests` 由约 1.5 s 回到约 30 ms，Stream 积压消失。**落单上限基本不变（约 10/s）**，因为落单之间仍在活动行 X 上串行；积压会转移到 MQ 待发送或 Broker 消费积压。TPS 收益估计 ≤ 约 7%，来自 bridge 的 S 不再插入落单的 X 队列。这是估计值。

### (c) 按活动批量落单

- 做法：消费端按活动分组（例如每批 ≤20 条）。一个事务内按排序依次锁定各买家的决策行，再锁一次活动行；逐条检查黑名单、已拥有、关闭、下架、时间；`sold_count` 按条件一次加 k（剩余库存不足时，按请求顺序分配，其余 SOLD_OUT）；订单、权益、请求、回执批量写入。整批提交后一起 ACK，失败则整批重投，靠回执保证幂等。
- 预期收益：每批只付一次锁和一次提交。按 20 条/批粗估可达 100/s 以上，**缺证据**，需要原型实测。
- 代价：改动最大。需要处理批内部分失败（单条异常不能拖垮整批）、多行 INSERT 的生成主键回填、批内唯一键冲突的预检，以及决策行加锁顺序；故障注入测试面最大。

## 3. 对关键业务语义的影响

| | (a) 条件 UPDATE 置后（含去 `fk_order_campaign`） | (b) 去 `fk_purchase_campaign` | (c) 按活动批量落单 |
|---|---|---|---|
| 关闭活动 | `closeCampaign` 是 `UPDATE … WHERE state='OPEN'`。落单的条件 UPDATE 是当前读：关闭未提交时等待，提交后匹配 0 行、回滚并以 CAMPAIGN_CLOSED reject；落单先提交时，关闭等待其提交。结果与现在一致：订单要么在关闭之前提交，要么被拒绝。 | 无影响 | 与现在相同：整批在活动行 X 下检查一次状态 |
| 下架 | 唯一的下架路径是 `RelationshipService.offline`，它在同一事务里把帖子置为 OFFLINE 并通过 `offlineInTransaction` 关闭该帖的 OPEN 活动。落单早先读到的帖子状态可能已过时，但以活动关闭为准，条件 UPDATE 会拒绝，语义不变。RED 需覆盖"落单读到 PUBLISHED 后下架提交"这一交错。 | 无影响 | 与现在相同 |
| 退款库存归还 | `completeRefund` 的加锁顺序是：决策行 → 退款 → 订单 → 活动 X → `tryDecrementSold`。落单仍先锁决策行、最后锁活动行，顺序一致，不构成环。增减都是原子 UPDATE，计数正确；退款是否回补 Redis 的判断（OPEN 且未过结束时间）仍在活动 X 下，不变。 | 无影响 | 加 k 与退款减 1 都是原子操作；同样不构成环 |
| 黑名单最终复核 | 现状：`block()` 不触碰活动行；落单的 `activeBlacklist` 是语句执行时的已提交读，非锁定。从读到提交约 90 ms 内提交的拉黑看不到。**这个窗口现在就存在，并非由活动行锁保证。** (a) 不改变它，读仍在插入之前。 | 无影响 | 窗口扩大到整批耗时；建议在条件 UPDATE 前用一条查询对全批复核一次 |
| 引用完整性 | `fk_order_campaign` 去掉后，订单的 `campaign_id` 来自已校验的请求；对账加孤儿检查 | 见 (b) | 不需要改外键 |

## 4. 对比与推荐

| | 改动量 | 落单上限（估计） | bridge 等待 | 主要风险 |
|---|---|---|---|---|
| 现状 | — | 约 10/s（实测 10.0–10.3） | P50 约 1.5 s | — |
| (b) | 1 个迁移 + 对账检查 | 约 10/s（≤ +7%） | 约 30 ms | 人工删库产生孤儿行 |
| (a) 保留订单外键 | 落单重排 | 约 11/s（+8%） | 缩短 | 小 |
| (a) 去订单外键 | 落单重排 + 迁移 | **约 24/s（约 2.4 倍）** | 缩短 | 0 行时的回滚重判路径；下架交错 |
| (c) | 消费端与落单重构 | 粗估 100/s 以上（缺证据） | — | 部分失败、幂等、加锁顺序 |

**推荐：(b) + (a)（去掉订单外键的版本）。**一个 V26 迁移删除 `fk_purchase_campaign` 和 `fk_order_campaign`，落单改为条件 UPDATE 置后，对账加孤儿引用检查。关闭活动、下架、退款和黑名单语义保持不变，上限估计从约 10/s 提到约 24/s。(c) 暂缓，只在目标超过约 24/s 时再做。

如需分别归因收益，可分两步：先只删除 `fk_purchase_campaign` 并复测 B 档，再做 (a)。也可以一轮完成，但两部分收益无法拆开。

RED 应覆盖的契约（草案）：
- 落单期间关闭活动、或下架提交时，不产生成功订单。
- 条件 UPDATE 影响 0 行时整体回滚，并按 CLOSED / ENDED / SOLD_OUT 正确拒绝。
- 并发落单与退款交错时不死锁，`sold_count` 与已付订单一致。
- 最后一件库存的并发争抢不超卖。
- 对账能发现孤儿引用。
- 小规模复现中，落单与 bridge 插入不再出现活动行锁等待。

## 5. RocketMQ 生产者启动预热（首批发送 11–18 s，超出 10 s 超时）

1. **启动预热**：在 `producer.start()` 之后拉取销售 topic 的路由（`fetchPublishMessageQueues`）并建立 broker 连接，有时间上限并记录耗时；预热成功前不让扫描器开始发送，就绪探针也不报就绪。
2. **慢启动**：启动后的前 N 秒或前 N 条只按 1 个槽、1 行领取，避免冷启动时一批占用多个租约（C 档一批 8 行用掉 24.9 s 租约）。
3. **记录真实耗时与原因**：`sendChunk` 目前吞掉了异常，发送失败的原因因此是缺证据；应记录异常类型和耗时。RocketMQ 同步发送的超时并不限制整个调用的耗时（实测 18.5 s）。如果需要硬性截止时间，可以改用异步发送加自有等待上限；超时后迟到的 ACK 可能造成重复投递，由消费端幂等吸收。正确性不依赖这一点：确认时逐行校验活租约。
4. **环境排查**：本轮 Broker 健康探测持续需要 4.4–6.8 s；首批慢发送的同一时段，OutboxRelay 对 NameServer 和 Broker 都出现了 `RemotingTimeoutException`。建议检查 Docker Desktop 中 broker 容器的资源和 `brokerIP1` 网络路径。

## 6. 环境基线：本机单行热点的物理上限（2026-11-19 23:39）

工具：`infra/jmeter/CommitBaseline.java`。只使用独立库 `lockbench.commit_bench`，不碰业务数据，不改刷盘配置；与应用走同一路径（Windows 上的 JDBC → Docker 映射端口 23306），READ COMMITTED，关闭自动提交。每个连接先预热 50 次，再测 1000 次"UPDATE + COMMIT"，共跑 2 轮。证据：`target/commit-baseline-20261119-233916/run1.json`、`run2.json`（私有）。

服务器配置（只读取记录）：MySQL 8.4.13，`innodb_flush_log_at_trx_commit=1`，`sync_binlog=1`，binlog ROW 格式，`O_DIRECT`，doublewrite ON，数据在 Docker 卷上。测量期间 C 档后端 PID 49824 仍在运行（空闲，扫描器每秒轮询），开始前可用内存 3742 MiB。

| 场景 | 每秒事务数（两轮） | COMMIT P50 / P95 / P99 (ms) | UPDATE P50 / P95 (ms) | 单事务 P50 / P95 (ms) |
|---|---|---|---|---|
| 单连接、同一行 | 57.0 / 53.2 | 13.8 / 23.7 / 34.0；14.9 / 25.7 / 38.0 | 1.7 / 4.6；2.0 / 5.2 | 15.6 / 27.3；17.0 / 31.1 |
| 4 连接、各更新不同行（合计） | 129.6 / 118.4 | 25.8 / 46.6 / 57.5；27.2 / 51.4 / 81.6 | 1.6 / 3.9；1.9 / 5.1 | 27.5 / 50.1；29.2 / 55.9 |

解读：
- 单行热点在本机的物理上限约 **53–57 次/秒**：持锁区间只剩"UPDATE + 提交"，约 17 ms。这是方案 (a) 在空闲环境下的理论上限。
- 4 个连接并发时，合计吞吐约为单连接的 2.2–2.3 倍（组提交摊薄了刷盘），但单次提交的 P50 从约 14 ms 升到约 26 ms。负载下落单事务实测的提交均值约 33 ms，与此一致：其他事务在共享刷盘。
- 因此方案 (a) 的上限取决于负载下的提交耗时：14 ms 时约 55/s，33 ms 时约 24/s（§2 按后者估计）。实际值会落在这两者之间，偏向后者，因为 bridge、发送和缓存同步的提交同时在刷盘。
- 方案 (c) 能突破这个上限的原因是：每次提交摊给多行；单次提交的物理成本（约 14–33 ms）不变。

### 6.1 补充：8 / 16 连接各更新不同行（2026-11-20 00:05）

同一工具、同一独立库，参数 `1,4,8,16`，每连接预热 50 次、测 1000 次，跑 2 轮。证据：`target/commit-baseline-20261120-000515/run1.json`、`run2.json`（私有）。开始前可用内存 3271 MiB；C 档后端 PID 49824 仍在运行（空闲）。

| 连接数（各更新不同行） | 合计提交/秒（两轮） | COMMIT P50 (ms) | COMMIT P95 (ms) | COMMIT P99 (ms) |
|---|---|---|---|---|
| 1 | 52.2 / 54.0 | 15.2 / 14.5 | 23.9 / 27.7 | 40.5 / 37.3 |
| 4 | 96.6 / 120.4 | 34.2 / 28.0 | 62.0 / 49.9 | 113.4 / 63.3 |
| 8 | 186.1 / 216.2 | 37.6 / 32.3 | 61.5 / 54.5 | 83.2 / 66.5 |
| 16 | 327.1 / 306.7 | 41.9 / 43.6 | 67.7 / 72.9 | 92.1 / 119.3 |

解读：
- 组提交随并发继续扩展：从 1 到 16 个连接，合计提交次数约为 6 倍；单次提交 P50 只从约 15 ms 升到约 42 ms。到 16 个连接仍未出现平台期，所以本机至少能承受约 **300 次提交/秒**（全系统合计）。
- 4 连接这一档两轮差异较大（96.6 / 120.4），首轮 P99 达 113 ms；8 连接以上更稳定。
- 结论：下一步的限制不在刷盘，而在单行热点。只要不同事务不排在同一行锁后面，全系统的提交预算约 300 次/秒；用每单提交次数换算落单上限见 `docs/perf-evidence/commerce/campaign-lock-v26-20261120.md`。

## 7. 锁问题解决后的对照计划

- 同一 JAR、同一套关卡和指标，三档：**B（cap 20 / 并发 1）、并发 2（cap 20 / 2）、C（cap 20 / 4）**。`run_dispatch_comparison.py` 需要新增并发 2 档。
- 每档 60/s × 60 s，排空后逐请求对账，对账加入孤儿引用检查。
- 若落单上限升到约 24/s，B 档发送能力（按阶段耗时估计约 14/s）会成为瓶颈，三档应开始拉开差距。
