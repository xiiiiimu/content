# Stream→MySQL 批桥接验证（2026-11-19）

状态：三档真实测量、排空与逐笔对账已完成，正确性和证据核验通过。保守选择批次20；整体持续成交容量仍未达目标。未提交、推送、合并。

## 冻结协议与实现

实施契约见 `docs/commerce-stream-batch.md`。保留 V23 与所有历史证据，补充用户确认的永久错误隔离协议，不将身份冲突视为幂等成功。

- `SaleBridgeBatch` 按配置最多读 1/20/57 条（可配置范围 1–200），有几条处理几条。活动与请求集合读取、fence、请求及待发送记录批写，一批一次短事务。正常新批实际 7 条 SQL；原实现每消息 5 条 SQL、一次提交。新批增加持久化阻断的安全成本，不能将新构建 size=1 当作旧 V23 性能。
- Redis 读取/ACK、隔离库存释放及 MQ 发送不在新桥接事务内；HTTP Lua 预占/XADD 后直接 PROCESSING。原缓存同步任务的既有事务边界未在本轮重写。
- 成功批提交后才能 ACK。回滚保留 pending；提交后 ACK 前故障重放不重复请求和待发送记录。混代批可推进当前代，旧代未知请求 pending 等受控恢复，generation 失败不等于永久坏消息。
- V24 增加隔离审计、reservation fence、隔离门禁时钟。审计按 Stream ID 幂等，保存原始 body、原因和首次请求引用；审计提交失败不 ACK。
- 释放只能使用 Lua 预占完成后保存的可信绑定，核对当前身份、generation、状态、数据库无订单/需裁决请求，先提交 BLOCKED fence，后执行幂等释放。无绑定不能凭冲突 payload 归还库存。APPLYING 等不完整释放保持 HOLD。
- 证明不足时持久化 HOLD 并暂停可确认范围；未知范围全局暂停入口/新结算。恢复重装门禁，保留预占预算，阻止迟到落单。正常消息可独立持久化。人工解除 HOLD 不属于本轮 API。

## 修改文件

应用：`domain/SaleBridgeBatch.java`（新增）、`domain/SaleIngress.java`、`domain/SaleService.java`、`infrastructure/SaleBridgeMapper.java`（新增）、`infrastructure/SaleEntryRedis.java`、`infrastructure/SaleConfiguration.java`、`infrastructure/SaleLatency.java`、新增 Flyway `V24__sale_bridge_quarantine.sql`。

测试：`CommerceIngressRevisionIT.java`、`MySqlMigrationIT.java`；测量：`run-java-commerce.ps1`、`run_v23_performance.py`、`analyze_v23_performance.py`、`V23Events.java`、新增 `bridge_batch_metrics.py` 与 `run_bridge_comparison.py` 及对应工具测试。以上 Java 路径位于 `src/main/java/io/github/xiiiiimu/content/`；测试位于 `src/test/java/io/github/xiiiiimu/content/`，工具位于 `infra/jmeter/`。不覆盖原有未提交改动。

## RED/GREEN 与回归

证据根：`target/bridge-batch-20261119-022551/`，公开机器摘要 `docs/perf-evidence/commerce/jmeter/bridge-batch-20261119-022551/regression-results.json`。

RED 两项已可编译，在真实 MySQL/Redis 上分别观察到“预期一次提交，实际两次”和“提交后 ACK 前只持久化一条，预期两条”；另三项隔离行为 RED 失败，均无环境/编译错误。

最终相关完整 Maven verify：55 项集成测试中 54 通过、1 项真实 MQ 20 秒等待超时；单元测试 52 项中 51 通过、1 可选 FFmpeg 跳过。未将此轮写作成功。失败时客户端存在 NameServer 超时/消费者启动重试；未修改超时和生产线程池，独立复核两项真实 MQ 测试均通过。计时包装补入最终构建后 `SaleLatencyTest` 与 package 成功。此前完整 GREEN verify 也有保留，不替代最终失败记录。

最终入口/桥接真实测试 36 项通过，覆盖批大小 1/20/57、批内重复、已有 clientKey 冲突、整批提交前失败、提交后 ACK 前失败、混代处理与受控恢复、坏消息审计前后故障、实际 ACK 后模拟回复丢失、可信孤立库存只释放一次、冲突 payload 指向 PAID、部分释放 HOLD、pending 接管以及既有购买/退款行为。Python 工具 33 项通过。

## 测量契约

同一不可变 JAR：`content-v24-c1c7cd41f07c.jar`，SHA256 `c1c7cd41f07c565582d89fad3e4445e3cd0822c5e6fbf6a3260df07cd9ee83d8`。源码快照 190 文件与工作区逐一相同，原暂存区与 V23 快照相同，见 `build-manifest.json`。

每档：新活动/素材、库存 4000、独立 clientKey、4000 已隔离买家，正式最多 3600 笔，目标 60/s、固定 60 秒、64 工作线程、HTTP 超时 30000ms、每人一次。另活动/64 账号同 JMeter JVM 预热；登录/准备在窗口外。三档共用同一 launcher 参数，仅切换 batchSize；每档排空对账后才重启下一档。后台持续运行，不使用同步 barrier 冒充固定到达率。

JFR 仅自定义分段、批次元数据、GC 和 DataLoss，持续覆盖排空，无完整客户端 JFR 或线程快照。订单提交必须有成功结算根、insertOrder/finishRequest、真实 JDBC commit 返回且无回滚，并与 PAID 订单逐项匹配。批提交按已成功提交元数据中的 reservationId 关联 JDBC commit 返回；元数据事件时间和 paid_at 都不替代提交时间。覆盖不足输出缺失，不补造 TPS。

SQL 统计同时给正常批和固定窗口全部桥接（准备、空扫描、缓存同步、隔离）开销；不推测自动提交 SELECT 次数。采样锁等待为离散观察，不宣称无未采中等待。排空确认给观察上界；客户端最终 SUCCEEDED 仅后置有界轮询能观察的部分，不能由轮询未见成功推断业务失败。

小规模预检：16 正式 +4 预热，正式全部成交，订单/库存/权益及原 clientKey 重放一致；HTTP/桥接/订单提交证据覆盖完整，无系统错误，排空。正式样本 HTTP P95 131ms；实际批大小 1–2，未强制等满。

## 历史基线边界

V23 A3 的 100 个正常持久化事件：500 SQL、100 提交，根耗时 P50/P95/P99 192/802/1416ms，SQL 合计 118/467/646ms，提交 24/59/153ms。该轮负载、库存及预热与本轮不同，仅用于说明逐条往返开销，不计算跨版本提升百分比。原 V23 B 提交证据不完整，不用 paid_at 补造其窗口 TPS。

支付、退款仍模拟；FFmpeg 容器清理未验证。原暂存/未暂存工作、离线复算工具和历史证据保留。

## 三档正式实测结果

全部使用上述同一 JAR，按 1→20→57 顺序，每档排空、对账通过后再开始下一档。三档实际到达率为目标的 94.31%–96.22%，均超过90%，但不是精确60/s；无追赶突发，不能宣称已测得精确60/s。输入数量略有差异，不作高精度因果或统计显著性结论。

|批次上限|活动/素材|正式样本|窗口启动QPS|窗口HTTP完成QPS|HTTP P50/P95/P99 ms|发射跨度ms|系统错误|
|---|---|---|---|---|---|---|---|
|1|111/124|3427|57.12|57.10|12/60/105|59997|0|
|20|113/126|3465|57.73|57.73|12/49/80|59993|0|
|57|115/128|3395|56.58|56.57|10/57/128|59984|0|

所有正式样本均 HTTP 200/PROCESSING 受理，最终成功率100%；售罄、其他业务拒绝、401、5xx、连接错误、超时均0。幂等重放各3笔在窗口外，不混入表中QPS；均返回原 reservationId、未增加订单。20档有1个 sampler 开始时间落在窗口外，其他两档0，原始值保留；HTTP completion 按实际完成时间归窗。

|批次|正常事务/提交|正常SQL总数|SQL/持久化请求|实际批数/均值/范围|批事务 P50/P95/P99 ms|窗口桥接条/s|全程桥接条/s|窗口全部桥接SQL/显式提交|
|---|---|---|---|---|---|---|---|---|
|1|3427/3427|23989|7.00|3427/1.00/1–1|100/380/539|2.20|2.56|2122/337|
|20|175/175|1225|0.35|175/19.80/2–20|134/523/686|38.05|42.29|1855/308|
|57|118/118|826|0.24|118/28.77/1–57|162/582/835|56.08|55.77|1819/302|

1档3427批均1条。20档173批20条、另2条/3条各1批。57档实际118批、均值约28.77条，只有15批达到57条，范围1–57；完整分布见各档 `bridge-batch-metrics.json`。三档正常批无回滚。全部桥接列另计准备、空扫描、缓存同步及隔离SQL；与正常批列的统计范围不同，不能用后者冒充全部工作。批事务最大耗时分别718/831/2126ms，仍有尾部风险。

|批次|窗口唯一提交订单|窗口成交TPS|全程成交TPS|HTTP后排空上界s|HTTP开始→提交 P50/P95/P99 ms|客户端观察成功/总数|
|---|---|---|---|---|---|---|
|1|132|2.20|2.56|1279.44|684433/1229801/1268804|417/3427|
|20|272|4.53|4.86|653.56|314335/647729/685192|879/3465|
|57|245|4.08|5.06|613.58|325328/614431/648104|812/3395|

全程成交TPS分母为首个正式HTTP开始到最后唯一订单 JDBC commit 返回；HTTP后排空是持续观测的确认上界。paid_at 记录点单列在各档 `windowPaidAtRecordOrders`，不用于替换提交。客户端最终结果：仅发压结束后有界轮询120秒，16并发、每批最多100次且至少间隔1秒；未观测者为右删失，不代表订单失败，分位数不是所有买家的完整最终等待分布。轮询调用数为11800/12000/12000，平均97.76/99.92/99.90次/s，全部在正式HTTP窗口之后，原始尝试时间在 `client-result-polls.json`。

### 库存、订单及权益

|批次|初始库存|请求/PAID订单/有效权益|售出/剩余|重复订单/重复权益|PROCESSING/HELD|订单提交覆盖|
|---|---|---|---|---|---|---|
|1|4000|3427/3427/3427|3427/573|0/0|0/0|3427/3427|
|20|4000|3465/3465/3465|3465/535|0/0|0/0|3465/3465|
|57|4000|3395/3395/3395|3395/605|0/0|0/0|3395/3395|

请求—订单—权益逐笔对账无差异，库存恒等式成立，重放未新增订单。各档最后全局 Stream pending/lag、MQ待发送、PROCESSING、HELD、未应用缓存任务、未解决隔离均0；详见 `measurement-verification.json`。Redis Stream 历史 length 为证据保留，不等于消费积压，没有为了排空而裁剪 Stream。

### 窗口边界附近的积压

以下为边界后分步采集的附近快照，不是精确边界原子值；数据库、Redis可能在采集中继续推进。每格为Stream lag/pending、数据库待发送/租约中、数据库PROCESSING。

|批次|边界|采样起止偏移ms|Stream lag/pending|MQ待发送/LEASED|DB PROCESSING|
|---|---|---|---|---|---|
|1|start|16…4193|112/0|0/0|1|
|1|end|36…2951|3292/0|1/0|0|
|20|start|46…3257|49/20|1/1|3|
|20|end|2…1784|1102/20|2047/0|2028|
|57|start|38…5347|27/57|1/0|2|
|57|end|19…2445|0/0|3146/0|3147|

|批次|Stream lag/pending峰值|MQ待发送峰值|DB PROCESSING峰值|活动表/缓存任务锁等待采样累计|
|---|---|---|---|---|
|1|3293/1|1|2|31/50|
|20|1206/20|3065|3065|6/38|
|57|134/57|3147|3148|4/29|

锁等待累计为离散样本中的等待观察次数，可能反复看到同一等待，运行长度也不同，不等于独立锁冲突数或可直接比较的冲突率。未采到不能证明不存在。窗口边界精确积压仍不可观测；JFR逐笔提交归窗不受这些快照偏移影响。

### 资源与分段

|批次|Tomcat忙线程/当前/连接峰值|HTTP排队峰值|Hikari活跃/等待峰值|Heap峰值MiB|JVM进程CPU峰值|GC最大ms|最大观测间隔ms|
|---|---|---|---|---|---|---|---|
|1|15.0/100.0/73.0|2.0|5.0/0|167.88|7.90%|26|5205|
|20|18.0/100.0/73.0|1.0|6.0/0|91.32|18.38%|8|3207|
|57|16.0/100.0/73.0|1.0|6.0/0|172.21|17.69%|10|3035|

配置保持 Tomcat max100、连接上限20000、accept-count5000、Hikari max120/min30、20消费线程、50ms扫描周期。采样均未见Hikari等待，HTTP排队峰值仅1–2，不能据此提高线程池。应用CPU归一化口径来自Actuator，非Windows整机CPU百分比；主机内存、线程状态、TCP等原样保存在 `observations.ndjson`。

|批次|应用HTTP P50/P95/P99 ms|Redis Lua端到端 P50/P95/P99 ms|发送领取 P50/P95 ms|MQ send P50/P95 ms|发送确认 P50/P95 ms|
|---|---|---|---|---|---|
|1|10/51/100|7/47/93|68/245|11/36|48/188|
|20|10/42/68|7/38/63|97/265|10/33|53/169|
|57|8/47/109|6/42/89|94/254|10/31|54/153|

三档抢购POST应用内SQL均0，鉴权P95均1ms。57档比20档增加的尾部主要见于Redis调用端到端区间（P99 89对63ms），GC最大10ms，不能用GC单独解释128ms HTTP P99。该计时包含Redis执行、网络与调度，现有证据不能再分解，更不能凭单次跑次断言由大批事务直接导致。未扣除连接、排队或脚本影响。

## 选择与剩余瓶颈

保守保留默认 `bridge-batch-size=20`，当前独立JAR已恢复该配置并保持运行（PID 34108，readiness UP，最终所有积压为0）。20档将正常事务从每请求一次降至实际175次，HTTP P95/P99在本次三档中最低，窗口成交TPS最高。57档进一步降低桥接SQL和Stream积压，但成交全程TPS仅由4.86到5.06，窗口成交TPS反而4.08；批事务P95/P99及HTTP尾部更高，现有单次比较不足以证明其端到端收益更好。57保留为显式可选配置，不删除其证据。

瓶颈已经从单条桥接向后移动：20/57档MQ待发送及PROCESSING峰值约3000，发送端只有一个调度执行者，逐笔领取与确认各有独立事务。领取P95约254–265ms、确认153–169ms，实际MQ发送P95仅31–33ms；Hikari不排队，不能把全部积压归咎于Broker或连接池。活动行锁与缓存任务竞争仍被采到，但当前证据不支持先按活动分区消费：应先独立评估批量领取、受限并行发送/确认及缓存任务往返，保持ACK/token/故障恢复契约，并单独验证。此处仅建议，未扩展实现。

正确性及提交证据三档PASS；不宣称达到60笔/s持续成交或30秒排空容量目标，三档都没有达到该排空目标。没有重复5,000人同步压测，没有更改HTTP超时或放宽错误判定。完整Maven verify曾失败一次、关联MQ复核通过的事实保留，不改写为一次全绿。

## 证据与复跑

每档目录保存 `summary.json`、`bridge-batch-metrics.json`、`supplemental-analysis.json`、`commit-evidence.json`、`reconciliation.json`、JTL、JMeter/GC日志、JMX/配置、连续资源与积压、轮询、重放和验证结果。原始服务日志/JFR及账号凭据只留本地 `target/bridge-batch-20261119-022551/perf-private/`；不要将私有CSV/meta或启动命令中的凭据提交Git。脱敏运行配置见 `runtime-snapshots/`。

```powershell
$run = Get-Date -Format yyyyMMdd-HHmmss
python infra/jmeter/run_bridge_comparison.py --jar target/bridge-batch-20261119-022551/runtime/content-v24-c1c7cd41f07c.jar --private "target/bridge-batch-repro-$run" --public "docs/perf-evidence/commerce/jmeter/bridge-batch-repro-$run"
# 只离线复算某档，不发压
python infra/jmeter/analyze_v23_performance.py docs/perf-evidence/commerce/jmeter/bridge-batch-20261119-022551/b20
python infra/jmeter/bridge_batch_metrics.py docs/perf-evidence/commerce/jmeter/bridge-batch-20261119-022551/b20
```

复跑器会检查并保留未知API进程；已有队列未排空则停止。每次新目录、新活动/clientKey，复用已授权测试账号并普通登录刷新，不清库。真实MQ回归命令、首次失败与后续成功日志见 `regression-results.json` 和私有回归日志。未来复跑需再次确认资源条件；本轮不再新增跑次。

最终检查：`final-environment.json` 已核对独立JAR指纹、配置20、readiness UP、队列全0及原暂存区未改变。`git diff --check` 对既有启动脚本日志路径行的末尾空格提出警告，未为格式检查改写原有工作；不将该检查写作全通过。
