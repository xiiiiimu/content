# MQ 待发送任务批量领取 / 发送 / 确认：A/B/C 同配置对照（2026-11-19）

约束文档：`docs/commerce-mq-dispatch-batch.md`（租约 30 s 不续租、单次发送超时 10 s、客户端重试 0、领取上限 20 但受空闲发送槽与剩余租约约束、发送前预算 10 s+5 s、逐行按 ID/状态/token/活租约确认、原退避、无 DEAD）。本轮未改动任何冻结决策。

## 1. 结论

| | A 串行（原实现） | B 批量 cap20 / 并发1 | C 批量 cap20 / 并发4 |
|---|---|---|---|
| 窗口提交 TPS | 5.55 | **8.05** | 7.40 |
| 全程提交 TPS | 6.25 | 10.02 | **10.33** |
| 请求→提交 P95 / P99 | 500.3 s / 527.9 s | 278.0 s / 286.3 s | 265.3 s / 275.0 s |
| 窗口结束后排空 | 492 s | 289 s | 277 s |
| MQ 待发送峰值 | 3046 | 51 | 20 |
| 租约违规 / 结果事务失败 | 0 / 0 | 0 / 0 | 0 / 0 |

- **推荐默认配置：`content.sale.dispatch-mode=batch`、`dispatch-batch-max=20`、`dispatch-concurrency=1`（B）。** 相比 A：全程 TPS +60%（6.25→10.02），窗口 TPS +45%，排空时间 −41%，请求→提交 P95 −44%。C 与 B 吞吐持平（全程 +3%、窗口 −8%，均在单次运行的波动范围内，未做重复运行，不据此判断优劣），但空领取多 2.8 倍、单批租约占用最高 24.9 s（见 §6），没有收益只多风险。
- **积压去向：没有转移到"Broker 消费积压"。** B/C 的 MQ 待发送几乎清空（窗口均值 21 / 3），Broker 消费积压窗口均值仅 37.6 / 28.2 条（峰值 52 / 54，≈ 20 个消费线程 + 本地已拉取未提交），积压转移到了 **Redis Stream（尚未落库的预占）**：Stream lag 窗口均值 A 503 → B 1447 / C 1532，峰值 655 → 2951 / 2990。
- **剩余瓶颈：活动行 `material_sale_campaigns` 热点锁。** 消费者落单事务 `lockCampaign … FOR UPDATE` 在 B/C 的 P50 为 1556 / 1410 ms（A 中不在前 8 慢阶段，各 SQL P50≈6 ms）；bridge 的 `insertRequests` P50 由 A 的 31 ms 升到 1552 / 1483 ms。`material_purchase_requests.fk_purchase_campaign` 外键检查需对活动行加共享锁，与落单的排他锁互斥；活动行锁等待采样 B 26 783 次 / C 23 456 次（A 仅 1 次）。这是推断链（外键定义 + 按表锁等待采样 + 两侧阶段耗时同步升高），未直接抓取锁持有者对应关系。

## 2. 运行说明与证据目录

- **A 档**：`docs/perf-evidence/commerce/jmeter/mq-dispatch-20261119-122940/A`（含同序列 smoke），已完成并对账通过，不重跑。
- **B/C 档**：原序列在 12:58:47 B 档 JMeter 预热刚开始时，整个进程树被 Claude Code 后台内存压力回收终止（无 `sequence-failure.json`，`results.jtl` 为空）。事实与残留检查见 `mq-dispatch-20261119-122940/interruption.json`：残留后端 PID 1800 已先保存日志与 JFR 后停止；B 档正式活动 123 未被触碰，预热活动 124 64/64 对账一致。**B/C 在中断后于新目录 `docs/perf-evidence/commerce/jmeter/mq-dispatch-20261119-185405` 用同一 JAR、新活动和 clientKey 重跑**（`--profiles B C --skip-smoke`），活动 123/124 未复用。
- 同一 JAR：`target/mq-dispatch-20261119-083643/runtime/content-v25-90d1a3eb48f9.jar`，sha256 `90d1a3eb48f94bea59750d7a3b115c3a39e591a784f84298280a350e7d70aa4e`（三档 `config.json.jarSha256` 一致）。各档只通过启动参数切换 `dispatch-mode/batch-max/concurrency`；bridge batch 20、消费线程 20、HTTP 超时等全部不变，由 `safe-runtime.json` 指纹核对。
- `config.json.sourceRevision` 字段为脚本里遗留的固定标签 "V24-bridge-batch"，不代表实际版本；以 jarSha256 和 Flyway V25 校验为准。
- 后端 PID：A 22844，B 35780，C 49824（C 档后端在写报告时仍在 28080 运行，未停止）。
- 活动：A 121/122，B 125/126，C 127/128（正式/预热）。各档买家取自同一 4000 账号池，每档新素材、新活动、新库存、新 clientKey（含活动 ID），买家对该档素材无历史订单或权益；不是全新账号。
- 负载：每档 60/s × 60 s 窗口，64 worker，同 JVM 预热，排空后逐请求对账，下一档仅在排空与对账通过后开始。

## 3. 分档指标

### 3.1 入口与提交

| | A | B | C |
|---|---|---|---|
| 到达率 / 窗口 HTTP QPS | 57.44 / 57.45 | 58.31 / 58.32 | 58.02 / 58.03 |
| HTTP P50 / P95 / P99 / max (ms) | 8 / 47 / 145 / 444 | 9 / 44 / 88 / 208 | 10 / 46 / 88 / 170 |
| 样本 / HTTP 200 / 系统错误 | 3447 / 3447 / 0 | 3499 / 3499 / 0 | 3482 / 3482 / 0 |
| 窗口内提交订单 | 333 | 483 | 444 |
| 窗口 / 全程提交 TPS | 5.55 / 6.25 | 8.05 / 10.02 | 7.40 / 10.33 |
| 请求→提交 P50 / P95 / P99 (s) | 292.5 / 500.3 / 527.9 | 152.9 / 278.0 / 286.3 | 148.8 / 265.3 / 275.0 |
| 提交时间覆盖（窗口+排空） | 3447/3447，缺失 0 | 3499/3499，缺失 0 | 3482/3482，缺失 0 |
| JFR DataLoss | 0 | 0 | 0 |

到达率略低于 60/s 是起搏器"不追赶"策略的结果（三档一致）。提交时间由服务端 JFR `jdbc.commit` 返回时刻逐订单匹配，覆盖窗口与全部排空期，三档无缺口。

### 3.2 领取批次分布（领取次数，键 = 一次领取得到的行数）

| | 分布 | 有行领取 / 空领取 |
|---|---|---|
| A | {1: 3512, 0: 279} | 3512 / 279 |
| B | {2: 1780, 1: 4, 0: 1060} | 1784 / 1060 |
| C | {8: 357, 4: 173, 2: 3, 0: 2923} | 533 / 2923 |

B 每次最多 2 行（1 空闲槽 × 2），C 最多 8 行（4 × 2），无超额领取。

### 3.3 发送路径 SQL / 提交次数与耗时

窗口内（语句开始时间落在窗口内）：

| | A | B | C |
|---|---|---|---|
| 发送行数（mq.send） | 334 | 524 | 468 |
| 发送路径 SQL 总数 | 2345（7.0/行） | 2567（4.9/行） | 2450（5.2/行） |
| 提交次数 | 670（2.0/行） | 领取 314 + 确认 262 = 576（1.1/行） | 领取 442 + 确认 71 = 513（1.1/行） |
| 回滚 | 0 | 0 | 0 |
| 领取阶段 P50/P95/P99 (ms) | 73 / 221 / 400 | 67 / 205 / 333 | 52 / 203 / 283 |
| 确认阶段 P50/P95/P99 (ms) | 50 / 155 / 384 | 60 / 198 / 352 | 86 / 262 / 298 |
| 提交耗时 P50/P95 (ms) | 27 / 64 | 领取 29/62，确认 33/75 | 领取 4/42，确认 36/70 |
| mq.send P50/P95/P99/max (ms) | 8 / 31 / 70 / 424 | 9 / 35 / 52 / 104 | 15 / 52 / 108 / 203 |

全程（含预热与排空）：A SQL 25 421、提交 7303（3512 行，2.08/行）；B SQL 20 294、提交 4629（3564 行，1.30/行）；C SQL 18 977、提交 3989（3554 行，1.12/行）。B/C 中每次空领取仍产生 4 条 SQL（guard、unknownHoldCurrent、expired、due）+ 1 次提交，C 的空领取占其领取 SQL 的大部分。

### 3.4 发送结果与租约

| | 发送成功 | 失败（回 PENDING 原退避） | 预算不足未发 | 确认漏行 | 重试漏行 | 结果事务失败 | 单批租约占用 P50/P95/P99/max (ms) |
|---|---|---|---|---|---|---|---|
| A | 3511 | 1 | — | 0 | 0 | 0 | 128 / 353 / 527 / 11 786 |
| B | 3563 | 1 | 0 | 0 | 0 | 0 | 140 / 336 / 556 / 15 634 |
| C | 3546 | 8 | 0 | 0 | 0 | 0 | 153 / 421 / 783 / 24 945 |

所有失败均发生在各档预热阶段、正式窗口之前，是该后端启动后第一批销售消息发送（见 §6），之后按原退避重发成功；三档最终订单数 = 请求数，无重复订单。

### 3.5 消费者落单事务与锁等待

| | 落单事务 P50/P95/P99/max (ms) | 提交 P50/P95 (ms) | lockCampaign P50/P95/P99 (ms) | 锁等待采样（有等待样本/总样本） | 按表累计观测 |
|---|---|---|---|---|---|
| A | 256 / 654 / 916 / 2607 | 26 / 56 | （不在前 8 慢阶段） | 23 / 320 | cache_jobs 22，campaigns 1 |
| B | 1828 / 2832 / 3421 / 4265 | 30 / 55 | 1556 / 2426 / 3010 | 175 / 239 | **campaigns 26 783**（单样本峰值 190），cache_jobs 5 |
| C | 1707 / 2838 / 4451 / 8034 | 28 / 50 | 1410 / 2276 / 3202 | 168 / 216 | **campaigns 23 456**（单样本峰值 171），cache_jobs 2 |

锁等待为 2 s 离散采样 `performance_schema.data_lock_waits`，同一等待会被重复计数，只用于比较量级。`material_sale_dispatch` 三档均未观测到锁等待。bridge `persist-batch` 事务 P50：A 133 ms → B 1620 ms / C 1560 ms，主要耗时在 `insertRequests`。

### 3.6 积压变化（Stream / MQ 待发送 / Broker / PROCESSING）

| | 窗口起点 | 窗口终点 | 峰值 | 窗口内均值 |
|---|---|---|---|---|
| A Stream lag | 0 | 573 | 655 | 503 |
| A MQ 待发送 | 0 | 2539 | 3046 | 1077 |
| A PROCESSING | 0 | 2540 | 3047 | — |
| A Broker 消费积压 | 4 | 4 | 5 | 4.0 |
| B Stream lag | 21 | 2951 | 2951 | 1447 |
| B MQ 待发送 | 5 | 0 | 51 | 21 |
| B PROCESSING | 7 | 40 | 65 | — |
| B Broker 消费积压 | 0 | 42 | 52 | 37.6 |
| C Stream lag | 0 | 2990 | 2990 | 1532 |
| C MQ 待发送 | 0 | 0 | 20 | 3 |
| C PROCESSING | 0 | 27 | 38 | — |
| C Broker 消费积压 | 8 | 30 | 54 | 28.2 |

Broker 消费积压 = 消费组 `content-sale-v1` 各队列 brokerOffset − consumerOffset 之和，5 s 采样一次（A 127/128、B 89/90、C 85/86 次成功，失败的是采样器启动连接时的超时，已记录在 `broker-lag.ndjson`）；已拉取未提交峰值 A 5 / B 52 / C 54，与 Broker 积压基本重合，说明 Broker 积压就是消费线程与本地缓存里在等活动行锁的消息，不是 Broker 堆积。PROCESSING 只统计已落库的请求；B/C 中大量请求仍在 Redis Stream 未落库，客户端看到的仍是"处理中"。

**回答：** B/C 的积压没有从"MQ 待发送"转移到"Broker 消费积压"，也没有被缓存在发送端；它转移到了 Redis Stream（bridge 落库前）。原因是落单事务与 bridge 落库在活动行上互相等锁，批量发送让落单并发更高，从而挤压了 bridge。

### 3.7 对账

三档 `correctnessPass=true`：样本数、买家唯一、无系统错误、逐请求对账无不一致、账本不变量、已排空、Redis 库存、重放检查全部通过。

| | 已付订单 | 有效权益 | 剩余库存 | 遗留 PROCESSING |
|---|---|---|---|---|
| A | 3447 | 3447 | 553 | 0 |
| B | 3499 | 3499 | 501 | 0 |
| C | 3482 | 3482 | 518 | 0 |

### 3.8 资源

| | 门槛开始可用内存 | 档结束可用内存 | 运行中观测最低 | Broker 健康探测（3 次，ms） |
|---|---|---|---|---|
| A | 4462 MiB | 3974 MiB | 3635 MiB | 5625 / 5000 / 5937 |
| B | 4957 MiB | 4210 MiB | 3798 MiB | 5312 / 4672 / 4953 |
| C | 4190 MiB | 4127 MiB | 3563 MiB | 5031 / 5219 / 6281 |

每档开始前 Testcontainers 容器为 0。门槛未降低。

## 4. EXPLAIN 与两项评估

证据：`target/mq-dispatch-20261119-083643/explain-20261119-192419/explain.json`（私有）。测量时 `material_sale_dispatch` 25 417 行全为 SENT（空积压，正是稳态中占多数的空领取场景）；`EXPLAIN ANALYZE` 会执行语句，所以对去掉 `FOR UPDATE SKIP LOCKED` 的只读形式执行，不加锁。

| 查询 | 访问路径 | ANALYZE 实际耗时 |
|---|---|---|
| 串行 `next`（OR 形式） | `ix_sale_dispatch_due` range：`(PENDING AND available_at_ms<=now) OR (state='SENDING')`，**SENDING 部分扫描所有 SENDING 行再按 lease 过滤，且 Using filesort** | 0.032 ms |
| 批量 `expired` | `ix_sale_dispatch_sending_expired` range `(SENDING AND lease_until_ms<=now)`，无 filesort | 0.029 ms |
| 批量 `due` | `ix_sale_dispatch_pending_due` range `(PENDING AND available_at_ms<=now)`，无 filesort | 0.025 ms |
| 发送前请求查询（主键） | const 主键查找 | <0.001 ms |
| 候选：按批 `IN` 取载荷 | PRIMARY range | 0.028 ms |

- **拆分领取 SQL + 复合索引（已按冻结方案实现于 V25）：** 空积压下两者都在微秒级，差别不可测；收益在积压大、SENDING 行多时体现（OR 形式会扫描全部 SENDING 行并排序）。负载中的领取阶段 P50 由 A 73 ms 降到 B 67 / C 52 ms，主要差异来自每次领取摊到多行而非单条 SQL 变快。拆分的另一目的（过期 SENDING 保留份额、不被 PENDING 饿死）由确定性测试覆盖（§5）。
- **去掉发送前的购买请求查询：** 实测每行 P50 3 ms / P95 10 ms（C 5 / 18 ms），全程合计 A 15.2 s、B 15.8 s、C 24.4 s 发送线程时间，不在任何锁内。可改为每个领取批一次 `IN` 主键查询（或在领取时连表取载荷），载荷字段不可变，不影响"重投保持原事件身份与载荷"。预期收益：每行省 3–5 ms 发送线程时间，**不会提升提交 TPS**（瓶颈在活动行锁）。建议暂不做；若做，单独一轮 RED/GREEN，不与本轮混合。本轮未改动。

## 5. 测试结果

- **RED**（`target/mq-dispatch-20261119-083643/RED.log`、`RED-2.log`，在桩实现副本 `red-build/` 上运行，环境健康、编译通过）：领取提交 3 vs 1、确认提交 2 vs 1、满载超额领取 5 vs 2、预算不足仍发送 2 vs 1、确认事务失败被当作 ACK、部分失败领取提交 3 vs 1、过期 SENDING 被 ID 顺序饿死——均因缺少目标行为失败。并发租约、接管/旧 token、领取后崩溃三项在桩上即通过，属保护性测试。说明：新行为的实现先于其 RED 编写，RED 通过独立桩副本获得（与上一会话 `target/mq-dispatch-20261119-074710/RED-target.log` 方法相同）。
- **GREEN**（`GREEN.log`）：15/15（CommerceDispatchBatchIT 10、CommerceDispatchBatchRealMqIT 2、MySqlMigrationIT 3）。
- **第一次回归被系统因内存不足终止**：日志保留为 `REGRESSION-killed-partial.log`、`regression-killed-reports/`。其中 1 个失败为测试缺陷：`batchPartialSendFailureConfirmsAndRetriesInOneTransactionEach` 断言两行按购买顺序发送。发送顺序不属于协议保证——约束文档未规定顺序；领取按"过期 SENDING 优先、再按 `available_at_ms, reservation_id`"取行，`reservation_id` 为随机十六进制；并发 4 时各块在发送池上并行。已改为 `containsExactlyInAnyOrder(ids.get(0),ids.get(1))`（每行恰好一次、无多余），未放宽为 `contains`。"过期 SENDING 不被待发 PENDING 饿死"由独立确定性测试 `batchExpiredSendingKeepsAShareAgainstAlwaysDuePending` 覆盖（把 `reservation_id` 最大的行设为过期 SENDING，`claimBatch(2)` 必须包含它）。
- **完整回归**（`target/mq-dispatch-regression-20261119-120059/`，按类分批、failsafe `-Xmx1g`、每批前资源门槛、每批后确认容器退出）：6/6 批通过，共 100 个测试，0 失败。

| 批次 | 测试 | 结果 |
|---|---|---|
| CommerceDispatchBatchIT + MySqlMigrationIT | 10 + 3 | 通过 |
| CommerceDispatchBatchRealMqIT | 2 | 通过 |
| CommerceIngressRevisionIT | 36 | 通过 |
| CommerceRealMqIT | 38 | 通过 |
| MaterialCommercePurchaseIT | 9 | 通过 |
| MaterialCommerceRefundBlacklistIT | 2 | 通过 |

- 工具测试：`infra/jmeter` 下 36 个 Python 单元测试通过。

## 6. 剩余风险与问题

1. **实际发送耗时可超过 10 s 发送超时。** 三档各自第一批销售消息（预热期，窗口前）出现 11.4 s（A）、12.1 s（B）、16.4–18.5 s（C，4 槽并行冷启动）的 `mq.send`。C 中一批 8 行：首轮发送约 6–8 s，预算检查通过（剩余 >15 s）后第二轮发送 16–18 s，单批租约占用 24.9 s，已吃掉大部分 5 s 确认预留但未超过 30 s 租约；8 行全部失败、按原退避回 PENDING 后重发成功，`retryMissed=0`。正确性不依赖该假设（确认/重试都按活租约逐行校验，过期即记为漏行，不伪造成功），但"10 s 发送 + 5 s 预留"在冷启动时不成立。失败异常类型未记录到日志或 JFR：**缺证据**。同时段后端日志有 OutboxRelay 对 NameServer/Broker 的 `RemotingTimeoutException`，Broker 健康探测本身需 4.4–6.8 s，说明本机 Broker 响应偏慢。
2. **空领取开销**：C 有 2923 次空领取（每次 4 SQL + 1 提交），B 1060 次。
3. **活动行热点锁**（§1、§3.5）是当前吞吐上限，超出本轮冻结范围，需另立方案评估（例如缩短落单持锁区间、调整外键检查与落单加锁的关系），不在本轮实现。
4. 每档单次运行，无重复，B 与 C 的小差异不作结论。

## 7. 改动文件（本任务）

- `src/main/java/io/github/xiiiiimu/content/domain/SaleDispatcher.java`（新）
- `src/main/java/io/github/xiiiiimu/content/domain/SaleIngress.java`（`claimBatch` / `sentBatch` / `failedBatch` / `Outcome`；串行方法未变）
- `src/main/java/io/github/xiiiiimu/content/infrastructure/SaleIngressMapper.java`（`expired` / `due` / `claimAll` / `sentAll` / `retryAll` / `holding`）
- `src/main/java/io/github/xiiiiimu/content/infrastructure/SaleLatency.java`（JFR `content.SaleDispatchBatch`）
- `src/main/java/io/github/xiiiiimu/content/infrastructure/SaleConfiguration.java`（`dispatch-mode` 开关，默认 serial）
- `src/main/java/io/github/xiiiiimu/content/domain/SaleService.java`（`sender()` 访问器）
- `src/main/resources/db/migration/V25__sale_dispatch_split_claim_indexes.sql`（新）
- `src/test/java/io/github/xiiiiimu/content/CommerceDispatchBatchIT.java`、`CommerceDispatchBatchRealMqIT.java`（新）、`MySqlMigrationIT.java`
- `infra/jmeter/`：`SaleDispatcher` 相关工具 `ConsumerLagSampler.java`、`mq_dispatch_gate.py`、`dispatch_batch_metrics.py`、`test_dispatch_batch_metrics.py`、`run_dispatch_comparison.py`、`run_dispatch_regression.py`（新）；`run_v23_performance.py`（滞后采样器、就绪握手）、`V23Events.java`（导出 dispatch.tsv）、`run-java-commerce.ps1`（`-DispatchMode/-DispatchBatchMax/-DispatchConcurrency`）

未提交、未推送。

## 8. 复跑命令

```powershell
# 回归（按类分批，failsafe -Xmx1g，每批前资源门槛）
python infra/jmeter/run_dispatch_regression.py --out target/mq-dispatch-regression-<ts>

# 对照（同一 JAR；A/B/C 或指定档）
python infra/jmeter/run_dispatch_comparison.py --jar target/mq-dispatch-20261119-083643/runtime/content-v25-90d1a3eb48f9.jar `
  --private target/mq-dispatch-20261119-083643/perf-private-<ts> --public docs/perf-evidence/commerce/jmeter/mq-dispatch-<ts> `
  --classpath target/mq-dispatch-20261119-083643/runtime-classpath.txt --profiles B C --skip-smoke

# 单档离线指标
python infra/jmeter/dispatch_batch_metrics.py docs/perf-evidence/commerce/jmeter/mq-dispatch-20261119-185405/B
```
