# 5,000 人抢购 HTTP 长尾本地诊断（2026-11-18）

项目 E:/content；保留本轮开始时的未提交版本。未提交、暂存、推送或访问其他项目。原报告和历史跑次原样保留。

## 1. 验收结论

原 P95=41.049 秒确实来自 POST-purchase 的 HTTP elapsed；不是 Transaction Controller、结果轮询或起跑屏障的混合值。但它也不是纯服务端执行时间。

本轮补充了分段 JFR、受控诊断 ID、Tomcat 排队指标、访问日志和客户端等待关联；常规运行不自动启动采集。四个新 5,000 人跑次均得到 100 笔 PAID、100 份 ACTIVE 购买权益、剩余库存 0，零重复、零 PROCESSING/HELD；仅一个新跑次满足原有同步门槛。客户端类解析/初始化、HTTP 客户端初始化、脚本编译和 JVM 停顿有直接证据。原始跑次缺少逐请求服务端日志，不能事后精确拆出那 41 秒各阶段占比。

客户端初始化试验、单独禁用 JMeter inline cache 试验均在 8 单小测通过，在 5,000 人下同步失败。前者接线已撤回并归档；后者虽消除了 ICBufferFull，却不能保证同步，只保留显式诊断开关，不默认启用。没有宣称有效性能提升。购买授权、库存、退款和结算事务未作业务修改。

## 2. 原始定位与统计口径

原报告：[flash-sale-final-20261118-024645.md](flash-sale-final-20261118-024645.md)。原活动 91、帖子 104。

原始 JTL：[results.jtl](jmeter/flash-5000-purchase-t5000-indy-20261118043509/results.jtl)；对应 JMeter 日志和 GC 日志在同目录。旧服务端日志：[final-java.log](jmeter/flash-acceptance-20261118024645/final-java.log)。旧实测配置：[environment-final-observed.json](jmeter/flash-acceptance-20261118024645/environment-final-observed.json)。原构建指纹见同目录 build-manifest.json。

JTL 恰好 5,000 条，label 全部为 POST-purchase；JMX 不含 Transaction Controller，purchase 场景没有执行 GET-purchase-result。屏障属于 PreProcessor，速率等待属于 Timer，均在 sampler 的 sampleStart 之前。HTTPHC4Impl 本地字节码确认：setupClient 在 sampleStart 前；readResponse 和 sampleEnd 在后处理器之前。因此后处理器等待不直接计入它自身请求的 elapsed，但并行编译/类初始化可以阻塞其他仍在 HTTP sampler 内的线程。

分位数使用 nearest-rank，位置为 ceil(N×p)，不插值。Connect、Latency、elapsed 都按各组实际 JTL 计算。Connect 不是纯 TCP RTT；Latency 是 sampler 开始到客户端记录首批响应数据的时间，包含客户端调度/解析等待。短 JSON 响应中 Latency 常等于或接近 elapsed。

### 原始分组（毫秒，P50/P95/P99）

| 结果 | 样本数 | Connect | Latency | elapsed |
|---|---:|---|---|---|
| HTTP 200 成功受理 | 100 | 3580/7837/8544 | 8444/11516/12130 | 8444/11516/12130 |
| HTTP 409 SOLD_OUT | 4900 | 4787/18482/22391 | 25114/41115/43998 | 25114/41115/43998 |

HTTP 200 表示请求受理，不等于已经支付。4,900 个售罄请求均在 Lua 拒绝后结束，没有对应持久化购买请求或订单，这是设计路径，不是漏单。

### 原跑次最慢 10 笔

| clientKey | 本地开始 | 本地完成 | elapsed ms | Connect ms | Latency ms | 终态 |
|---|---|---|---:|---:|---:|---|
| jm-c91-u13605 | 04:37:08.322 | 04:37:52.667 | 44345 | 5491 | 44345 | 409 SOLD_OUT；无 reservation/订单 |
| jm-c91-u15128 | 04:37:08.331 | 04:37:52.646 | 44315 | 5658 | 44315 | 409 SOLD_OUT；无 reservation/订单 |
| jm-c91-u12198 | 04:37:08.335 | 04:37:52.644 | 44309 | 5980 | 44309 | 409 SOLD_OUT；无 reservation/订单 |
| jm-c91-u12825 | 04:37:08.322 | 04:37:52.629 | 44307 | 5205 | 44307 | 409 SOLD_OUT；无 reservation/订单 |
| jm-c91-u15141 | 04:37:08.335 | 04:37:52.641 | 44306 | 6177 | 44306 | 409 SOLD_OUT；无 reservation/订单 |
| jm-c91-u13303 | 04:37:08.391 | 04:37:52.676 | 44285 | 5086 | 44285 | 409 SOLD_OUT；无 reservation/订单 |
| jm-c91-u10797 | 04:37:08.402 | 04:37:52.659 | 44257 | 5906 | 44257 | 409 SOLD_OUT；无 reservation/订单 |
| jm-c91-u12919 | 04:37:08.392 | 04:37:52.646 | 44254 | 5170 | 44254 | 409 SOLD_OUT；无 reservation/订单 |
| jm-c91-u13436 | 04:37:08.422 | 04:37:52.671 | 44249 | 5422 | 44249 | 409 SOLD_OUT；无 reservation/订单 |
| jm-c91-u11584 | 04:37:08.426 | 04:37:52.672 | 44246 | 5628 | 44246 | 409 SOLD_OUT；无 reservation/订单 |

完整逐笔记录：[原跑次请求与数据库时间线](jmeter/purchase-latency-20261118055110/baseline-request-db-timelines.json)。100 个受理样本均匹配到真实购买请求和 PAID 订单；其余 4,900 个无持久化请求。

## 3. 新跑次与同步有效性

所有新正式购买参数保持：5,000 个不同买家、库存 100、一次购买、RampSec=1、Loops=1、WarmupThreads=0、PurchaseStartsPerSecond=0、BarrierGroupSize=5000、BarrierTimeoutMs=120000、连接/响应超时各 30000ms。原有请求启动跨度上限 5000ms 未调整。

后端保持 HTTP workers=100/minSpare=100、maxConnections=20000、Windows 首次监听 backlog hint=5000；Hikari 最大 120/最小空闲 30/等待 15 秒；JDBC 连接 10 秒、socket 60 秒、MyBatis 30 秒；Redis 2 秒；MQ 消费线程 20、扫描 50ms。后端 4 个逻辑 CPU、发压机其余 12 个，优先级和旧跑次一致。JMeter Java 17、indy=true、实际堆上限 3072MiB、栈 256KiB。

每轮都创建独立活动并正常刷新测试身份、执行 Preflight；未用 SkipPreflight。诊断/复测加入观测有额外开销，不能据此宣称纯后端性能提升。

| 跑次 | 活动/帖子 | ready / timeout | 请求启动跨度 ms | HTTP P50/P95/P99 ms | 同步结论 |
|---|---|---|---:|---|---|
| 原始 | 91/104 | 5000 / false | 3898 | 24926/41049/43967 | PASS |
| diagnostic | 92/105 | 5000 / false | 51443 | 59094/64564/66278 | FAIL：启动跨度超限 |
| probe | 94/107 | 5000 / false | 2920 | 35305/36968/37087 | PASS |
| fixed | 95/108 | 5000 / false | 65791 | 6803/28454/35360 | FAIL：启动跨度超限 |
| ic-off | 97/110 | 5000 / false | 28737 | 28856/33339/33686 | FAIL：启动跨度超限 |

diagnostic 增加服务端 JFR；probe 另加客户端 JFR 并做了一次线程快照，JFR 记录该 PrintThreads 操作约 2.242 秒，必须视为观测扰动。fixed 只保留服务端 JFR、GC/safepoint 日志，并试验一次正式窗口外的传输/脚本初始化。后者正式 JTL 仍只有 5,000 条，但启动跨度 65.791 秒，因此 28.454 秒的 P95 不能作为有效优化成绩。

ic-off 撤去初始化探针，只把发压 JVM 的 UseInlineCaches 改为 false，并由 VM.flags 实际确认；其余购买/后端/线程/堆/超时配置相同。该轮启动跨度 28.737 秒、P95=33.339 秒，仍不可用于有效性能提升比较。各轮缓存、连接池已运行状态也不是可重置的严格 A/B，本报告不计算生产性能提升百分比。

连接和响应超时各 30 秒，不是从 sampler 开始的总 wall-clock 截止。JVM 类初始化、解析和调度等待不能被这两个阶段超时完整限制；没有 Timeout 不等于总 elapsed 小于 30 秒。

5,000 线程 ready、5,000 次 sampler 启动、5,000 个 HTTP 响应、100 笔最终成交是不同概念。fixed 中采样到的服务端连接峰值仅约 3,631，也印证没有形成同样的同步负载。

### 各轮吞吐口径

| 跑次 | 请求发起 QPS | HTTP 完成 QPS | HTTP 200 QPS | paid_at 记录 TPS | 确认排空全程 TPS |
|---|---:|---:|---:|---:|---:|
| 原始 | 1282.709 | 112.653 | 2.253 | 2.494 | 2.131 |
| diagnostic | 97.195 | 73.803 | 1.476 | 1.663 | 1.431 |
| probe | 1712.329 | 134.488 | 2.690 | 3.327 | 2.608 |
| fixed | 75.998 | 73.380 | 1.468 | 6.195 | 1.432 |
| ic-off | 173.992 | 146.649 | 2.933 | 3.749 | 2.688 |

发起 QPS=样本数/(最后开始−最早开始)，不是 NIC 发包速率。HTTP 完成 QPS=响应数/(最后完成−最早开始)，包含售罄。paid_at TPS=唯一 PAID/(最后 paid_at−最早请求开始)，paid_at 是应用记录点，不是数据库 commit；全程 TPS 使用确认排空的观测时刻。它们都是库存 100 的有限突发统计，不能外推为持续成交容量。

## 4. 服务端与客户端分段

下表使用同步有效的 probe；不同人群、不同阶段的 P95 不可相加。

| 阶段 | 成功受理 P50/P95/P99 ms | SOLD_OUT P50/P95/P99 ms |
|---|---|---|
| sampler 开始→服务端 accept | 7330.564/7904.484/9507.692 | 7665.755/9423.445/9522.139 |
| accept→应用过滤器入口 | 2399.473/2656.123/2659.163 | 11954.018/25784.923/27047.807 |
| 应用过滤器入口→结束 | 654.655/1086.989/1115.926 | 249.972/485.411/564.811 |
| 每笔 SQL span 总和 | 385.236/738.047/760.343 | 146.684/325.272/385.099 |
| 每笔连接获取/诊断包装总和 | 5.582/37.988/52.932 | 0.015/36.405/118.679 |
| 每笔 Redis span 总和 | 23.119/48.599/62.027 | 78.67/222.673/273.282 |
| 服务器访问日志结束→JMeter 完成 | 25517/26706/26987 | 15112/24832/25544 |

应用内 SOLD_OUT 的 P95 仅 485.411ms；成功受理 1086.989ms。服务器访问日志结束到 JMeter 完成的 P95 分别为 24.832 秒、26.706 秒。accept→filter 包含客户端尚未写完请求、协议解析和调度，不能全部叫作 Tomcat 排队；实际执行器队列采样峰值为 624。sampler→accept 也不能直接当作纯网络 RTT。

已关联 20,000 个新正式请求的应用事件与访问日志，状态全部一致。JFR filter 结束与访问日志结束大部分仅相差亚毫秒，访问日志的 duration 字段单位是微秒，已由 begin/end 毫秒字段交叉核对。

### 发压机直接证据

probe 进程实际 Java 17.0.17、groovy.target.indy=true。并非再次误用 Java 21 或关闭 indy。

- HTTPSamplerBase.readResponse 的类初始化监视器等待，单次最长 12,895.559ms；监视器类型为 JVM 初始化锁 [I。
- HeaderGroup.iterator/HTTP 头处理也存在初始化竞争；同一记录中等待最长约 5,279.910ms。
- PublicSuffixMatcherLoader.getDefault / HttpClientBuilder 首次初始化监视器等待最长 4,824.957ms，位于 setupClient，发生在 sampleStart 之前，影响实际发起同步性。
- JSR223 编译缓存争用最长 6,577.406ms，发生在后处理器；不直接计入本线程 HTTP elapsed，但不能忽略并行编译对其他线程的影响。
- 客户端 SafepointBegin 最长约 5.274 秒，存在数秒级 HandshakeAllThreads；GC pause 本身不能覆盖这类等待。probe 的线程快照自身耗时已单独披露。

这些是跨线程等待总和，不能把总毫秒相加成单请求耗时；类加载事件也不是等量的唯一类定义。已按 JTL threadName 逐笔裁剪到 HTTP 区间，并对嵌套 ClassLoad/JavaMonitorEnter 求区间并集。

最慢请求 jm-c94-u13994：HTTP 37,158ms，已证实的客户端 JVM 等待并集为 13,035ms；另两笔分别为 13,150ms、13,204ms。SocketRead 同时包含服务端/网络/调度，未计入“确定客户端 JVM 等待”的并集。

详见 [客户端等待与请求关联](jmeter/purchase-latency-20261118055110/injector-request-waits.json)、[等待栈统计](jmeter/purchase-latency-20261118055110/injector-http-final-stacks.tsv)、[类加载统计](jmeter/purchase-latency-20261118055110/injector-http-final-classes.tsv)。原始客户端 JFR 留在 target/flash-observer-latency-probe-20261118055110/injector.jfr。

## 5. 逐笔时间线与时钟

Windows 应用、JMeter 均在同机；日志为 Asia/Shanghai，JFR 含 +08:00，HTTP Date 为 UTC；数据库业务毫秒字段由应用生成。[时钟探针](jmeter/purchase-latency-20261118055110/clock-probe.json)显示 MySQL epoch 落在主机查询前后区间内，HTTP Date 与主机秒级一致。

JMeter 使用 nanoTime 到 epoch 的换算。原跑次未保存逐次 offset 校准，无法追溯其每一笔精确跨 JVM 时钟误差；不能把旧日志不存在的接收/响应时间补出来。新跑次的 JFR、访问日志和客户端事件能够在同一毫秒时间线关联。[覆盖与时钟检查](jmeter/purchase-latency-20261118055110/clock-and-coverage.json)保留实际匹配数和差值，未假设主机之间零误差。

数据库 created_at、mq_sent_at、paid_at、receipt.created_at 均不是事务提交时刻。新 JFR 的 jdbc.commit 结束表示驱动已返回提交结果，仍不是数据库内部物理 commit 时间；访问日志结束也不是客户端收到 ACK。

### 原始最慢受理请求

clientKey=jm-c91-u13568，reservationId=af60be59c391413787dc36581fac1326，orderId=9691。JMeter 04:37:08.384 开始、04:37:20.560 完成（12176ms）；数据库 request.created=04:37:17.180，mq_sent=04:37:23.516，paid_at=04:37:24.544，receipt.created=04:37:24.544。最终 request=SUCCEEDED、order=PAID、receipt=SUCCEEDED。paid_at 比 HTTP 完成晚 3984ms；旧接收/响应/commit 时刻未知。

### 新跑次受理请求示例

clientKey=jm-c94-u13336，reservationId=9cf76e7a7d2c467189e54b74f290412e，orderId=9968。

| 事件 | 本地时间 |
|---|---|
| JMeter 开始 | 06:39:40.875 |
| TCP accept | 06:39:48.751 |
| 应用入口 | 06:39:51.169 |
| 受理 commit 返回 | 06:39:52.085 |
| 服务端响应日志结束 | 06:39:52.226 |
| 结算开始 | 06:40:06.763 |
| 结算 commit 返回 | 06:40:08.576 |
| 结算方法结束 | 06:40:08.590 |
| JMeter HTTP 完成 | 06:40:17.846 |

最终 SUCCEEDED/PAID/SUCCEEDED。完整 5,000 条新请求时间线：[probe](jmeter/purchase-latency-20261118055110/probe-request-db-timelines.json)；另见 diagnostic / fixed 同名文件。该示例清楚区分 HTTP 客户端完成与订单提交完成，二者不要求先后相同。

## 6. 实际购买路径与结算热点

HTTP 接受路径按当前 SaleService.purchase()：

1. 可信身份过滤器校验 Bearer 签名和有效期；该步骤无 SQL。
2. Redis gatewayPaused；SQL byClient(userId,clientKey) 优先处理持久化幂等。
3. SQL campaign；验证 OPEN。
4. 权威可见性读取：FeedMapper.visible，再对非作者执行 FeedMapper.authority，含关注状态、cutoff/history 判断，不能用缓存直接授权。
5. Redis Lua 资格/一人一单/库存预占。SOLD_OUT 在这里直接 409，没有 INSERT/commit。
6. 成功预占才在短 MySQL 事务内 insertRequest 并 commit，返回 PROCESSING。必要时 EXISTING/唯一键冲突会额外读取持久化请求。

HTTP 路径没有同步 RocketMQ send。持久化扫描器异步发送并标记 mq_sent，真实消费者执行 settle()。所以把所有售罄长尾归为 MQ RTT 或结算活动行锁不成立；它们可以通过 CPU/连接资源共享间接影响入口。

结算事务包括锁请求、消费回执、账户素材互斥/权益检查、lockCampaign、黑名单、条件库存更新、订单、权益、请求终态和消费回执，原子提交，之后 Redis confirm。原显式活动锁保持到提交，未删除、未替换成“无锁”假设。

| 结算阶段 | diagnostic P50/P95/P99 ms | probe P50/P95/P99 ms |
|---|---|---|
| 结算方法总耗时 | 6415.663/10512.957/11258.094 | 2621.271/5060.144/5191.538 |
| 活动锁查询（含等待/往返） | 5560.771/9304.697/10357.255 | 2228.273/4705.383/4843.552 |
| 锁查询返回→commit 返回 | 287.175/1284.109/1631.403 | 120.713/621.865/874.799 |
| JDBC commit | 65.18/314.865/476.175 | 29.082/189.751/262.963 |

锁查询耗时不是纯服务器锁等待，但 performance_schema 同时采到 material_sale_campaigns 的锁等待，峰值 171 条等待边；这是等待图的边数，不是 171 个 Worker。锁查询返回到 commit 返回是应用观测临界区，不等于数据库精确持锁时间。

MySQL slow_query_log=OFF、long_query_time=10s，未静默开启或修改。现有 MyBatis span 包含映射、网络和嵌套连接获取；无法把它冒充服务器纯 SQL 执行时间。jdbc.acquire 还包含诊断代理包装，因此以 Hikari 原生 pending/acquire/timeout 指标补充判断。没有逐请求关联的服务器慢 SQL/内部 commit/fsync 细分日志。

## 7. 资源观测与不可观测边界

| 指标峰值 | diagnostic | probe | fixed（无效同步） | ic-off（无效同步） |
|---|---:|---:|---:|---:|
| Tomcat 执行器队列 | 190.0 | 624.0 | 132.0 | 243.0 |
| Tomcat busy | 100.0 | 100.0 | 100.0 | 100.0 |
| Tomcat connections | 5005.0 | 5007.0 | 3631.0 | 5007.0 |
| Hikari active | 71.0 | 79.0 | 53.0 | 61.0 |
| Hikari pending | 40.0 | 0.0 | 0.0 | 49.0 |
| Hikari total | 82.0 | 102.0 | 81.0 | 82.0 |
| JVM live threads | 305.0 | 345.0 | 325.0 | 327.0 |
| Hikari timeout 累计计数 | 0.0 | 0.0 | 0.0 | 0.0 |
| JVM heap used（字节） | 235392872.0 | 285103328.0 | 297444320.0 | 293926760.0 |
| 进程 CPU 归一化值 | 0.1450002075021252 | 0.1296833799388613 | 0.08448750508800108 | 0.09928358069555938 |

进程 CPU 值为 JVM/系统归一化指标，不应直接当作所分配 4 个逻辑 CPU 的利用率百分比。后端正式窗口 STW GC 最大值：diagnostic=28.278ms，probe=22.180ms，fixed=21.378ms；新跑次完整事件在对应 phase-distributions.json 中。

### JMeter JIT 单参数验证

fixed 的整个 JVM 生命周期有 767 次 ICBufferFull，762 次与 HTTP 窗口重叠；重叠窗口累计 reaching-safepoint 16,642.366ms、at-safepoint 2,503.158ms。Cleanup 的 reaching 阶段另有单次 5,139.475ms。reaching 是线程同步延迟，不等于所有线程都已暂停，不能与重叠的 JFR monitor 时间再相加。

ic-off 已实际确认 UseInlineCaches=false，整个 JVM 与 HTTP 窗口的 ICBufferFull 均为 0；但 HTTP 窗口仍有 Cleanup/G1 同步等待，准备阶段还出现大量 CollectForMetadataAllocation。消除一个 VM 停顿来源并不等于修好了客户端全部初始化/调度问题，实际启动跨度仍超限。没有继续盲目增加堆、线程或超时。fixed-safepoints.json、ic-off-safepoints.json 和运行时 flags 已归档。

observer 对每项指标的读取是错开的，不能把同一 tick 的 active/pending/total 当作严格同一瞬间。采样峰值可能漏过短峰值；原始 ndjson 在对应 target/flash-observer-<runId>。Hikari timeout 使用 COUNT，非 VALUE。Windows TCP 状态已采集，但精确 listen backlog 当前占用不可观测；未把“无指标”记作零。

后端正式窗口 GC 的 STW pause 在几十毫秒量级，不能解释几十秒 HTTP 长尾；GC 并发周期耗时与 STW pause 也未相加。客户端可见多秒 safepoint/handshake，GC pause 统计单独不足以排除此类停顿。没有逐秒硬缺页、Windows 调度延迟或抓包级首次发包/响应到达时间，不能精确分割剩余客户端/内核等待。

## 8. 修复尝试、回归与改动

保留的改动：

- SaleLatency.java、SaleLatencyConfiguration.java：默认关闭的 JFR 事件，身份校验/SQL ID/连接获取/commit/Redis/入口与结束计时，Tomcat 队列 gauge。
- FeedIdentityFilter、SaleService、SaleRedis：仅增加分段作用域；LocalWindowsNioProtocol 增加 accept 事件。
- run-java-commerce.ps1：显式 SegmentTimings 开关，必须使用新的诊断目录；访问日志只含诊断 ID，无 Authorization。
- commerce-sale.jmx：X-Diag-Id 使用固定本轮 clientKey；run-commerce-jmeter.ps1 增加 safepoint 日志；purchase_capacity.py 读取排队指标。
- run-flash-acceptance.ps1：可选客户端 JFR、可选 DisableInjectorInlineCaches，仅诊断；两者均不默认启用。5,000 native threads 的 JFR/快照本身可能扰动，不作无开销基线。
- 分布、时间线、JFR 原生读取、等待区间关联与统计测试脚本；flash_acceptance.py 保留实验初始化证据检查，旧/常规跑次缺省不启用。
- SaleLatencyTest、MaterialCommercePurchaseIT：实际 JFR 生命周期及真实 MySQL commit/rollback 验证。

未减少或删除业务 SQL，没有改变 lockCampaign、授权、幂等、库存、支付、权益或退款规则。当前问题的主要 HTTP 证据含显著发压机等待，不应先凭 41 秒给数据库做无依据改写。

尝试：正式窗口前单次无凭据 POST（预期 401 UNAUTHENTICATED），初始化 HTTP 类并编译与主计划完全相同的后处理脚本；不购买、不扣库存，不写入正式 JTL。首次小测因预期错误码写为 AUTHENTICATION_REQUIRED 而主动停止，0 个正式请求；修正测试预期后 8 单通过，随后 5,000 人却未过同步门槛。实验源码在 experiment/ 归档，当前 JMX/runner 已撤去该接线，没有留一个伪通过的修复。

验证结果：

- 计时 RED 测试：可编译，断言预期 1 个 JFR 分段事件、实际 0，因目标行为缺失失败；未作提交。
- Maven verify -Pintegration -Dit.test=MaterialCommercePurchaseIT,MaterialCommerceRefundBlacklistIT：52 项单元测试，51 通过、1 项可选 FFmpeg 执行测试跳过；11 项指定真实集成测试全部通过。不是声称所有 Failsafe 套件都已重跑。
- 最后一次 Tomcat gauge 类型修正后，SaleLatencyTest 和 package 通过；启动后 gauge 返回有限值并完成真实跑次验证。
- Python 回归 14 项全部通过，覆盖原容量/对账检查及分位数、时长解析、嵌套等待去重、safepoint 纳秒单位和正式窗口过滤。
- 初始计时 smoke：8/8 受理及对账通过；初始化小测失败记录保留；修正预期后的 smoke：8/8，P95=430ms，初始化单独归档。
- inline-cache 关闭的小测：8/8，P95=985ms，对账一致。
- 四个新 5,000 人跑次均完成真实 MySQL/Redis/RocketMQ 链路及逐笔对账；同步有效性分别 FAIL/PASS/FAIL/FAIL。

## 9. 库存、订单、权益对账

| 新跑次 | HTTP 200 / SOLD_OUT | 401 / 5xx / CONNECT_FAIL / timeout | PAID / 有效权益 | 剩余库存 | 重复订单/权益 | PROCESSING / HELD |
|---|---|---|---|---:|---|---|
| diagnostic | 100 / 4900 | 0 / 0 / 0 / 0 | 100 / 100 | 0 | 0 / 0 | 0 / 0 |
| probe | 100 / 4900 | 0 / 0 / 0 / 0 | 100 / 100 | 0 | 0 / 0 | 0 / 0 |
| fixed | 100 / 4900 | 0 / 0 / 0 / 0 | 100 / 100 | 0 | 0 / 0 | 0 / 0 |
| ic-off | 100 / 4900 | 0 / 0 / 0 / 0 | 100 / 100 | 0 | 0 / 0 | 0 / 0 |

每笔核对 reservationId、userId、clientKey、campaign/post/merchant/price、订单、有效购买权益和 SUCCEEDED 消费回执。Redis 库存和请求终态与 MySQL 一致。原始每轮 reconciliation.json、redis-reconciliation.json、acceptance-report.json、barrier-stats.json、traffic-by-second.json、JTL/GC/JMeter 日志均保留。HTTP 200 没有被当作已支付。

## 10. 下一步修复方向与停止条件

1. 首先使发压端稳定：将脚本编译、HTTP 客户端纯本地初始化移到明确的准备阶段，并验证每个线程在屏障后直接进入有界计时的真实发送；连接建立仍应真实计入。单次初始化方案已证实不足，不能继续套用其 P95。优先验证每台较小线程数的多发压机方案；没有现成机器与同步证据，不声称已完成分布式 5,000 人。
2. 发压端每轮必须 ready=5000、timedOut=false、正式 sampler 启动跨度≤5000ms、唯一买家/样本均5000，系统错误为0，逐笔对账一致；任一失败即停止拿该轮作性能比较。
3. 对入口 SQL，可把元数据与权限检查合并为一个权威 SQL 快照，先写非作者/未关注/取关/重关注历史/offline 并发 RED 测试，保持错误顺序和授权语义。不可直接缓存授权、跳过 byClient 或把 SOLD_OUT 提到权限检查之前。本轮未实施这一业务修改。
4. 结算应先缩短活动锁后的固定读取和写入往返，保留请求/订单/库存/权益/回执原子性及关闭、退款恢复、幂等契约。条件 UPDATE 仍然持锁到提交，不能宣称锁已消失。按活动分区消费能减少同一活动的争抢，但单热点活动仍串行，须在可靠测量后单独验收，不能靠增加 Worker 掩盖。
5. 下一轮进一步观测客户端 safepoint、Windows 硬缺页/调度和真实网络收发时间；对 SQL 增加受控服务器端时间窗口采样。当前明确缺少这些分解，不应填造数字。

## 11. 证据与复现

- diagnostic：[flash-5000-purchase-t5000-latency-diag-20261118055110](jmeter/flash-5000-purchase-t5000-latency-diag-20261118055110/acceptance-report.json)，活动 92；[阶段分布](jmeter/purchase-latency-20261118055110/diagnostic-phase-distributions.json)，[JTL 分布](jmeter/purchase-latency-20261118055110/diagnostic-jtl-distributions.json)，[逐笔时间线](jmeter/purchase-latency-20261118055110/diagnostic-request-db-timelines.json)，[资源摘要](jmeter/purchase-latency-20261118055110/diagnostic-resource-summary.json)。
- probe：[flash-5000-purchase-t5000-latency-probe-20261118055110](jmeter/flash-5000-purchase-t5000-latency-probe-20261118055110/acceptance-report.json)，活动 94；[阶段分布](jmeter/purchase-latency-20261118055110/probe-phase-distributions.json)，[JTL 分布](jmeter/purchase-latency-20261118055110/probe-jtl-distributions.json)，[逐笔时间线](jmeter/purchase-latency-20261118055110/probe-request-db-timelines.json)，[资源摘要](jmeter/purchase-latency-20261118055110/probe-resource-summary.json)。
- fixed：[flash-5000-purchase-t5000-latency-fixed-20261118055110](jmeter/flash-5000-purchase-t5000-latency-fixed-20261118055110/acceptance-report.json)，活动 95；[阶段分布](jmeter/purchase-latency-20261118055110/fixed-phase-distributions.json)，[JTL 分布](jmeter/purchase-latency-20261118055110/fixed-jtl-distributions.json)，[逐笔时间线](jmeter/purchase-latency-20261118055110/fixed-request-db-timelines.json)，[资源摘要](jmeter/purchase-latency-20261118055110/fixed-resource-summary.json)。
- ic-off：[flash-5000-purchase-t5000-latency-ic-off-20261118055110](jmeter/flash-5000-purchase-t5000-latency-ic-off-20261118055110/acceptance-report.json)，活动 97；[阶段分布](jmeter/purchase-latency-20261118055110/ic-off-phase-distributions.json)，[JTL 分布](jmeter/purchase-latency-20261118055110/ic-off-jtl-distributions.json)，[逐笔时间线](jmeter/purchase-latency-20261118055110/ic-off-request-db-timelines.json)，[资源摘要](jmeter/purchase-latency-20261118055110/ic-off-resource-summary.json)。

- [访问日志（仅关联购买行）](jmeter/purchase-latency-20261118055110/purchase-access.csv)、[服务端脱敏日志](jmeter/purchase-latency-20261118055110/backend-diagnostic.log)、[源码/原始 JFR SHA256 与工作树保护](jmeter/purchase-latency-20261118055110/manifest.json)。完整私有日志、JFR、原始线程快照和初始/最终工作树快照在 E:/content/target/purchase-latency-20261118055110。
- [RED](jmeter/purchase-latency-20261118055110/timing-red.log)、[GREEN](jmeter/purchase-latency-20261118055110/timing-green.log)、[Maven verify](jmeter/purchase-latency-20261118055110/verify-instrumentation.log)、[gauge 修正验证](jmeter/purchase-latency-20261118055110/queue-gauge-build.log)、[Python 14 项回归](jmeter/purchase-latency-20261118055110/python-regression-final.log)。

只读重算示例（PowerShell，项目根目录）：

    python infra/jmeter/analyze_purchase_latency.py docs/perf-evidence/commerce/jmeter/flash-5000-purchase-t5000-indy-20261118043509 target/recheck-latency --campaign 91
    python -m unittest discover -s infra/jmeter -p 'test_*.py'
    java infra/jmeter/JfrStackSummary.java target/flash-observer-latency-probe-20261118055110/injector.jfr 1794955180800 1794955217978 target/recheck-injector

需要重新发压时必须重新造独立活动和有效测试 Token，不能直接重用本报告活动。实际执行参数见各轮 JMeter 日志与 barrier/window JSON；私有 run-diagnostic.ps1、run-probe.ps1、run-fixed.ps1、run-ic-off.ps1 记录本次命令。fixed 为失败实验，源码仅用于复核。ic-off 由现有 runner 传 DisableInjectorInlineCaches；它仅证明消除了 ICBufferFull，没有通过容量同步验收，不推荐默认启用。

补充说明：一次通用 JFR JSON 文本导出在 probe 跑次结束后的文本导出阶段（第三、第四轮发压之前）膨胀至约 10.68GB，短暂耗尽 E 盘；已停止导出，只移除本轮失败的冗余派生 JSON，原始 JFR SHA256、全部历史报告、JTL 和原始测试数据均保留。最后一轮在恢复空间后执行。改用 JDK RecordingFile 有界读取，20,000 条正式购买访问日志均成功关联，服务已确认 readiness=UP。处理记录见 [derived-export-cleanup.json](jmeter/purchase-latency-20261118055110/derived-export-cleanup.json)。

最终状态：本地诊断与必要验证完成；原 41 秒不能解释为纯后端执行。有效性能提升未获证明，单机 5,000 人发起同步可靠性仍未解决。保留全部结果，不提交、不推送，CI 未运行。

