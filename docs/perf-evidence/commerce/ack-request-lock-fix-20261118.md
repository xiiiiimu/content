# 发送确认与购买请求行锁解耦：本地验收

2026-11-18，Asia/Shanghai。按收紧后的范围，本轮完成已复现锁等待修复、当前改动相关回归，以及独立版本 JAR 的**一次 8 买家真实 MQ 验证**。下一轮 5,000 人发压暂停；没有提交、推送或合并。

锁修复与小规模对账：**PASS**。此前 96 秒发射跨度、151 次连接超时的跑次属于无效同步负载，单独归档，不能作为性能提升证明。

## 根因与最小修复

旧发送确认事务先更新 `material_sale_dispatch`，再查询数据库时间并更新 `material_purchase_requests.mq_sent_at_ms`。消费者落单事务也通过 `lockRequest()` 持有同一请求行 X 锁。发送确认因此可能等待消费者；发送线程串行执行时，等待会延后后续消息发送。

本次修改：

| 文件 | 变更 |
|---|---|
| `SaleIngress.java` | ACK 确认只提交 dispatch 条件更新，移除请求行更新及额外数据库时间查询。|
| `SaleIngressMapper.java` | 同一条条件 UPDATE 原子写入 SENT 与 `sent_at_ms`；SENDING、token、有效租约条件保持。|
| `V23__sale_dispatch_ack_timestamp.sql` | 新增 dispatch ACK 时间列；仅用已有真实历史值回填，不编造缺失时间，不修改已执行迁移。|
| `SaleMapper.java` | 删除已无调用的旧 pending-MQ 查询及请求行 ACK 标记方法，避免继续以旧列判断新投递。|
| `CommerceIngressRevisionIT.java` | 增加双连接真实 MySQL 行锁屏障回归。|
| `MySqlMigrationIT.java` | 覆盖新增迁移、升级、重复迁移与校验。|
| `infra/jmeter/verify_sale_ack_lock.py` | 新增隔离活动的小规模真实 MQ 行锁验证工具。|

每次成功 ACK 确认由 3 条 SQL 减为 1 条，保留原有一次提交。旧请求表列保留供历史读取；新投递状态与确认时钟以 dispatch 为准。相关统计脚本已按此读取。

消费者的请求、订单、库存、权益、终态和消费回执原子事务没有拆分；`lockCampaign()` 保留。没有调大线程池、放宽超时、改变 Broker ACK 成功判定或刷盘配置。

收紧范围前加入的缓冲 JFR 分段及统计修正保留并验证：后台 persist/dispatch 不再误计为 settle；订单提交取主事务提交，而非后续缓存同步提交；连接超时同时属于连接失败和超时；已有完整排空观测时不将报告收尾延迟记作积压。原失败跑次文件不重写。

## 锁等待证据与回归

RED：真实 MySQL 的独立连接持有请求行 X 锁，另一线程执行发送确认；`Future.get(2s)` 抛出 `TimeoutException`。失败来自已复现的行锁耦合，不是编译、Docker 或连接失败。

GREEN：相同测试在请求行锁释放前完成 SENT 确认，随后仍可正常落单。原有状态/token/租约、重复消费、事务回滚等回归保留。

| 验证 | 实际结果 |
|---|---|
| 默认 Java 单元测试 | 52 项：51 通过，1 项 FFmpeg 执行测试按原条件跳过；无失败。|
| 当前改动相关集成测试 | 37 项通过，无失败或跳过。|
| 脚本统计测试 | 21 项通过。|
| Maven verify | 首段完成入口 18 项及真实 MQ 2 项后执行中断；仅补跑未完成四类 17 项，verify 成功。两个执行段生产源码一致。|

六类集成测试分别为：入口/故障 18、真实 MQ 2、购买 9、退款/黑名单 2、素材 3、迁移 3。不是宣称全仓 M0–M9 的所有集成矩阵均已执行；前端本轮未重跑，CI 未运行。

机器可读结果：[regression.json](ack-request-lock-fix-20261118/regression.json)。RED 日志：`target/commerce-revision-20261118082417/ack-rowlock-red.log`；完整回归分段日志：`green-verify6b.log`、`green-verify6-remaining.log`。首次打包曾因运行 JAR 文件锁失败，保留该失败日志；完成事务后停止本轮旧服务，再从独立快照启动，没有修改测试判定来绕过。

## 独立版本与唯一一次小规模验证

独立只读 JAR：`E:\content\target\commerce-revision-20261118082417\runtime\content-v23-376b7ae3cf20.jar`。

SHA-256：`376b7ae3cf209dec1f36993c81d27685ab6de3014a687fdeee642d521c54efa5`。

当前监听 PID：24820；liveness/readiness 均 UP。JAR 位于独立 runtime 目录，后续 Maven 打包不会覆盖运行文件。见 [运行快照](ack-request-lock-fix-20261118/runtime-snapshot.json)、[健康检查](ack-request-lock-fix-20261118/backend-health.json)。

使用新建隔离活动 **102**、素材 **115**、8 个授权测试买家、库存 8。HTTP 购买与 Stream 桥接、MQ 客户端、Broker、消费者均走实际应用链路。

先以既有测试钩子暂停落单，再由测试 SQL 会话模拟消费者持有其中一条请求行 X 锁，对该条已发送记录做一次受控重投；未修改请求字段或 reservationId。此操作只针对本次隔离数据，不是生产人工重放入口。

| 现场证据 | 实际值 |
|---|---|
| 请求行锁会话 | 294 |
| SENT 可见时仍持有的请求行 X 锁 | 1 条，performance_schema 确认 GRANTED |
| 重投到 ACK 记录 | **97 ms**，同一数据库时钟 |
| ACK 确认状态 | SENT；当时尚未向锁会话发送 COMMIT，订单数为 0 |
| 该记录累计领取次数 / token | 3 / 4 |
| 最终请求 / 支付订单 / 活跃购买权益 / 回执 | 8 / 8 / 8 / 8 |
| 剩余库存 / 重复订单 / 重复权益 | 0 / 0 / 0 |
| PROCESSING、HELD、Stream pending/lag、dispatch 待发、缓存同步任务 | 全部 0 |
| 释放暂停后确认排空 | 3.516 秒 |

外部读取在 REPEATABLE-READ 下已看到 SENT，且另一会话请求行锁仍为 GRANTED，证明确认事务确已提交，并未等待该请求行锁。97 ms 是数据库记录点差值；提交完成由独立连接读到 SENT、另一会话仍持有请求行锁共同证明，不将记录点当成物理提交瞬间，也不作为容量或吞吐量结论。

完整脱敏结果：[small-real-mq.json](ack-request-lock-fix-20261118/small-real-mq.json)。

## 剩余问题：仅记录影响和建议

1. **初次投递曾自动重试一次。** 该条累计 3 次领取包含一次受控重投，另一次来自此前自动重试。最终对账一致；现有日志不足以确定首次重试原因。建议后续记录脱敏失败类型及逐尝试阶段耗时，本轮不增加实现或追加验证跑次。
2. **旧 5,000 人跑次发压不合格。** ready=5000 为真，但样本发射跨度 96.134 秒，且有 151 次连接超时。需要先验证发压机和 TCP 接入段，再谈有效容量对照；不靠放宽超时或线程池掩盖。
3. **旧报告排空口径有采集延迟。** 61.722 秒是最终确认上界；已有数据库/队列观测在最后 HTTP 完成前确认成交结束。缓存终态最终正确，但该跑次缺少精确的缓存任务排空时点。原报告保留，解释与修正另存，不据此宣称真实积压 62 秒。

4. **旧监控字段兼容。** 新发送不再填写请求表 `mq_sent_at_ms`，仓库内相关统计已切换 dispatch。仓库外若仍直接读取旧列，可能误报未发送，应同步核对；不会因此重新投递或改变订单终态。

上述发压问题详见[独立故障归档](jmeter/injector-failure-ingress-v2-20261118125422/README.md)。没有继续实现客户端预热、线程调度或新的并发阶梯。

## 可复跑命令（供审查，当前不再执行）

在 `E:/content` 的 PowerShell 7 中使用 Java 17。不要复用已耗尽活动，不要覆盖原报告；构建采用新的隔离目录。

```powershell
$env:JAVA_HOME='C:/Users/西木/.jdks/ms-17.0.17'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:CONTENT_REAL_MQ_IT='true'
$build="target/ack-repro-$(Get-Date -Format yyyyMMddHHmmss)/build"
New-Item -ItemType Directory $build | Out-Null
Copy-Item pom.xml "$build/pom.xml"
robocopy src "$build/src" /E /NFL /NDL /NJH /NJS /NP | Out-Null
# 针对性回归；若只审查行锁行为，可单独执行这一条。
& tools/apache-maven-3.9.6/bin/mvn.cmd -f "$build/pom.xml" test-compile failsafe:integration-test failsafe:verify -Pintegration '-Dit.test=CommerceIngressRevisionIT#brokerAckConfirmationDoesNotWaitForTheConsumerRequestRowLock'
# 需要完整相关回归时执行；不是本轮继续重跑的指令。
& tools/apache-maven-3.9.6/bin/mvn.cmd -f "$build/pom.xml" verify -Pintegration '-Dit.test=CommerceIngressRevisionIT,CommerceRealMqIT#realBrokerAckThenLostSenderConfirmationAndConsumerAckReplayOneOrder+unavailableRealProducerLeavesDurableRetryThenRealBrokerCompletes,MaterialCommercePurchaseIT,MaterialCommerceRefundBlacklistIT,MaterialFlowIT,MySqlMigrationIT'
```

当前独立 JAR 已运行，不要重复启动。未来服务停止后，可在新的 DiagnosticsDir 用 `infra/jmeter/run-java-commerce.ps1 -JarPath <独立快照> -DiagnosticsDir <新目录> -SegmentTimings` 启动。

只复跑小规模验证时，先生成新活动，再运行已归档的验证工具：

```powershell
$seed=@(& infra/jmeter/seed-commerce-data.ps1 -Buyers 8 -Stock 8 -Prefix ackrepro -ReuseCsvGlob 'target/jmeter-data/buyers-p5kstksml9.csv' -ReuseAuthorMeta (Get-Content target/flash-5000-meta.txt).Trim() -ThrottleLimit 4 -ReturnMetaPath)
$meta=[string]($seed | Select-Object -Last 1)
$evidence="docs/perf-evidence/commerce/ack-small-$(Get-Date -Format yyyyMMddHHmmss)"
New-Item -ItemType Directory $evidence | Out-Null
python infra/jmeter/verify_sale_ack_lock.py $meta "$evidence/result.json"
```

此工具会拒绝已使用活动及已存在证据文件；测试使用一条请求行锁和一次受控重复投递，最终释放锁与暂停钩子。不得用于生产业务。上述命令不包含 Token，Token 只从本地私有造数文件读取。
