# V23 性能验证（2026-11-18 → 2026-11-19，本地未提交版本）

结论：**正确性对账通过，性能/测量验收 PARTIAL。** A 实际发射未达到 400/s；B 完成有效 60 秒 HTTP 窗口，但后处理 WinError 206 导致提交观测缺口。该工具问题已修复并以真实 3,400 条数据验证；没有再发一轮购买来掩盖缺失。本轮未提交、推送或合并。

## 1. 环境与工作树

- Windows 服务可访问，实际执行 `git status --short`、`docker version`、本机 Java 检查及 readiness；不属于 shell 无法访问 Windows 服务的情形。
- 系统默认 Java 21；所有 JMeter、JFR 控制及导出明确使用 Microsoft Java **17.0.17**，JMeter **5.6.3**。
- 后端始终为 PID **24820** 的独立 V23 JAR，未重启、未重建业务实现：`target/commerce-revision-20261118082417/runtime/content-v23-376b7ae3cf20.jar`。
- SHA-256：`376b7ae3cf209dec1f36993c81d27685ab6de3014a687fdeee642d521c54efa5`，开始和结束均复核一致；Flyway V23。V23 发送确认只更新 `material_sale_dispatch`，不再更新消费者持锁的购买请求行。
- 开始和结束：runtime RUNNING、generation=6、Redis ready=1、repair=0，数据库与 Redis run_id 一致；Stream pending/lag、MQ 待发送、MySQL PROCESSING、HELD、缓存任务全部 0。历史 Stream 长度不是积压。
- 当前全部 Java 业务源及迁移与锁修复指纹一致。旧指纹中唯一改变的是测量工具 `infra/jmeter/flash_acceptance.py`（本轮修复 Windows 参数长度）。暂存区差异输出核对一致。旧未提交改动、离线复算脚本、旧跑次保留。
- 最终 readiness UP；诊断 JFR 已停止，后端保留运行。详见 [v23-perf-20261118-234151/final-environment.json](v23-perf-20261118-234151/final-environment.json)、[运行参数](v23-perf-20261118-234151/backend-runtime-options.json)。

实际运行参数：Tomcat 100 工作线程、acceptCount 5000、maxConnections 20000；Hikari 最大 120/最小 30；MQ 消费线程 20；扫描间隔 50ms。没有通过增加这些池或延长 30 秒 HTTP 超时改变成绩。

## 2. 预热、发压模型和无效准备记录

本轮新建专用计划，旧 `commerce-sale.jmx`、旧 runner 和同步 Barrier 标准没有改变。每轮一个 JMeter JVM、一个线程组、同一个真实 HTTP sampler：A 的 128 个线程/B 的 64 个线程各自先购买独立预热活动，再在预热阶段有界查询至 SUCCEEDED，屏障后发正式 POST。预热与正式用户集合不重叠。两轮可复用部分测试账号，但使用不同素材、活动和 clientKey；每轮内部买家唯一。

- A：2,000 正式买家，库存 100，目标 400/s，正式 2,000 笔。
- B：4,000 备用买家、库存 4,000，最多安排 3,600 笔，目标 60/s；到 60 秒硬截止，未发出的行不补发，实际 3,400 笔、600 个账号未用于正式请求，库存始终有成交机会。
- 有界线程数 + 最小间隔、无追赶突刺的模型会在 HTTP/线程调度变慢时降低实际到达率；它不是保证 400/s 的开放式发生器。目标值、实际值分别记录。
- 正式阶段只采集购买 POST。注册、登录、关注、素材上传、账号刷新均在窗口外。没有正式阶段结果轮询、完整客户端 JFR、线程快照或重型监听器。后台轻量指标采样约每 2 秒一次，原始时间保留。
- 原始 Windows park 常表现为 15–16ms；400 许可探针只有 64.651/s，开启 JVM ForceTimeHighResolution 仍为 64.626/s。精确末段等待后，独立探针为 398.452/s；真实 A 仍受调度/并发模型及 HTTP 等待限制。精确等待可能占一个核心，该成本未从任何实测耗时扣除。
- 一次 A 准备因 token 剩余不足 5 分钟被 preflight 拦住，正式样本 0。改为每轮临发前普通登录刷新，另存私有数据，不绕过检查。
- A2 在正式窗口前出现 **1,057.417 秒**整体观测中断；恢复时 warmup 产生 154 个 401，另有 105 个准备/屏障异常样本，无正式窗口，已作废。休眠、调度停顿、时钟变化的具体原因未获确认。随后加入临时防系统空闲休眠、30 秒控制循环中断作废保护，以及发请求前 token 检查；未永久修改电源设置。

这些无效准备与有效 A/B 系统错误分开；未删除任何异常样本。探针和模拟 HTTP 工具检查不是业务压测。

## 3. 有效 HTTP 结果

发射速率 = `(样本数−1)/(最后开始−最早开始)`；窗口 QPS = 窗口内完成的正常业务响应数 / 窗口秒数。A 窗口从约定正式放行时刻至最后 HTTP 结束后 1ms；B 为 **2026-11-19 00:41:57.681–00:42:57.681 +08:00**，严格 60 秒。

| 跑次 | 活动 / 素材 | 正式样本/唯一买家 | 发射跨度 ms | 实际发射/s | 正常完成 QPS | 最终 PAID | 剩余库存 |
|---|---|---:|---:|---:|---:|---:|---:|
| A 初轮（计时精度问题） | 104 / 117 | 2000 | 25406 | 78.682 | 78.635 | 100 | 0 |
| A 修正复测 | 108 / 121 | 2000 | 13504 | 148.030 | 146.574 | 100 | 0 |
| B 固定 60 秒 | 106 / 119 | 3400 | 59987 | 56.662 | 56.650 | 3400 | 600 |

A 修正复测窗口 13.645s，滚动一秒最大启动 254；目标 400/s 未达。B 窗口发起 56.667/s（目标的 94.44%），滚动一秒最大启动 61；全部开始点在窗口内，3,399 次响应在窗口内完成，1 次完成落到窗口之后。

下表均是 **HTTP sampler 的实际 elapsed**；Connect、Latency 为 JTL 原字段，未扣除脚本间接调度、连接或排队影响。没有 Transaction Controller 或结果轮询混入正式样本。

| 类别 | 样本 | 窗口完成 QPS | elapsed P50/P95/P99 ms | Connect P50/P95/P99 ms | Latency P50/P95/P99 ms |
|---|---:|---:|---|---|---|
| A 修正复测 受理 | 100 | 7.329 | 100 / 224 / 236 | 0 / 0 / 0 | 100 / 223 / 236 |
| A 修正复测 售罄 | 1900 | 139.245 | 57 / 1891 / 2891 | 0 / 0 / 0 | 55 / 1891 / 2890 |
| B 受理 | 3400 | 56.650 | 11 / 50 / 87 | 0 / 0 / 14 | 11 / 48 / 85 |
| B 售罄 | 0 | 0.000 | — / — / — | — / — / — | — / — / — |

有效 A 修正复测：100 个 HTTP 200、1,900 个 409 SOLD_OUT；受理率/总买家最终成交率均 5%，正常业务响应率 100%，受理后成单率 100%。B：3,400 个 HTTP 200、无业务拒绝；最终成单率 100%。A、B 有效正式窗口的 **401 / 5xx / CONNECT_FAIL / Timeout 均为 0**。幂等重放不混入正式窗口：每轮结束后另发 3 个已知 clientKey，均返回原 reservationId 和 SUCCEEDED，订单数不增加。

## 4. 三种耗时和 QPS/TPS

- **HTTP 响应耗时**：上表 JTL elapsed。
- **请求开始 → 服务端主事务提交返回**：JFR root/线程/区间关联到对应 reservationId、订单；必须同时有 insertOrder、finishRequest、无 rollback，取随后第一个 jdbc.commit 返回，排除后续缓存同步提交。它是 JDBC commit 返回证据，不是物理刷盘瞬间。
- **客户端首次看到 SUCCEEDED**：正式阶段结束后才进行有界查询，包含延迟开始查询的影响，不等于提交时刻。B 使用公平轮转、16 并发、每批最多 100、批间至少 1 秒、整体 120 秒上限；逐条尝试时间另存。

A 修正复测：完整主事务证据 **100/100**；窗口内提交 15 单，窗口成交 TPS **1.099**；全程成交 TPS（100 / 从首 POST 到最后主事务提交返回）**3.136**。请求至主事务提交返回 P50/P95/P99 = **24178 / 30804 / 31217 ms**。客户端首次观察 SUCCEEDED P95 = **31063 ms**，覆盖 100/100。

B：原连续录制覆盖正式窗口，并确认窗口内 **351 次**唯一订单主事务提交返回，即已确认提交 TPS **≥5.850**。窗口内 `paid_at` 记录点为 **352** 条（5.867/s），单列，不替代 commit。因后处理观测缺口，保守将机器报告的**完整窗口 TPS / 全程 TPS 标为 null**，不把确认下界宣称为完整值。

B 两段 JFR 合计保留 **1,066/3,400** 单的可关联主事务提交时间，**2,334 单缺失**；全体请求至提交的百分位缺失，不能将已记录子集外推为总体。最大订单 ID 的尾单提交证据也保留，但没有用它推断所有其他并发事务的 JDBC 返回顺序。

B 有界查询共 **11200** 次 GET，实际约 **92.858/s**；只在上限内观察到 **910/3,400** 个 SUCCEEDED。该已观测子集的客户端 P95 为 **149322 ms**；全体客户端 P95 缺失。其余请求已通过数据库和权益对账确认最终成功，不能倒填客户端观察时刻。

## 5. 积压、排空和对账

起止采集不是 MySQL/Redis 分布式原子快照。下列值各自带采集区间；Redis 已知正式用户 PROCESSING 用单次 Lua 原子读取，不扫全共享 hash。

| B 指标 | 起始附近 | 结束附近 |
|---|---|---|
| Redis 正式用户 PROCESSING | 29，Redis 时刻 W开始 +624ms | 3,047，W结束 +516ms |
| Stream | 原始起始快照见 JSON | lag 2,902 + pending 131 |
| MySQL 购买请求 | 原始起始快照见 JSON | 364 已持久化，354 SUCCEEDED，10 PROCESSING |
| MQ 待发送 | 原始起始快照见 JSON | PENDING 8 + SENDING 1，SENT 355 |
| 缓存任务 | 原始起始快照见 JSON | 2 |

MySQL/Stream 结束快照约 W结束 +49ms 至 +1,414ms；不能将上述不同时刻值强行相加或宣称恰好在边界发生。Redis 中的 PROCESSING 包含尚未持久化的预占，MySQL PROCESSING 只是已持久化未终结部分。

A 修正复测：末次 HTTP 后约 **19.365s 内**首次完整观测到排空（采样确认上界）。B 真实排空时刻缺失，只能确认在末次 HTTP 后 **378.144–452.673s** 之间；下界使用已记录尾部主事务，随后快照确认全部清空。上界不是实际积压时长，报告导出耗时不计为排空时间。

| 最终逐项对账 | A 修正复测 | B |
|---|---:|---:|
| 成功请求 / PAID 订单 / 有效 PURCHASE 权益 / 成功消费回执 | 100 / 100 / 100 / 100 | 3400 / 3400 / 3400 / 3400 |
| 库存上限 / sold / 剩余 | 100 / 100 / 0 | 4000 / 3400 / 600 |
| 重复订单 / 重复权益 | 0 / 0 | 0 / 0 |
| PROCESSING / HELD | 0 / 0 | 0 / 0 |
| Stream pending / lag、MQ 待发送、缓存任务 | 全 0 | 全 0 |
| clientKey、userId、reservationId、订单、权益、回执逐笔不一致 | 0 | 0 |

## 6. 已定位的瓶颈与 V23 效果

1. **发压端计时/调度**：默认 Windows park 的 15–16ms 量化已由探针复现并做针对性修正；真实 A 从 78.682/s 到 148.030/s，仍未达到 400。探针 398/s 不能冒充真实 HTTP 400/s。还存在有界并发模型、共享许可交接、HTTP 前调度等待；没有证据支持只靠增大线程池或超时解决。
2. **A 的长尾在后续请求，不是明显首次初始化**：每线程首个正式 HTTP P95 292ms，预热 HTTP P95 940ms；全部 Connect 为 0。售罄请求 sampler→应用入口 P95 **1708ms**，应用入口→退出 P95 **442ms**，退出→sampler结束 P95 **16ms**。这些是相关区段分布，百分位不能相加。本轮没有每次 TCP 接受事件/重型客户端栈，无法再细分发压调度、网络投递与 Tomcat 入站队列。
3. **B 第一主要后台积压是 Stream→MySQL 桥接**：结束时 Stream 约 3,033 条未完成桥接，而 MQ 待发送只有 9、MySQL PROCESSING 10。`SaleIngress.drain()` 逐条调用 persist；新请求有 guard、campaign、request、insertRequest、enqueue 等约 5 次 SQL 加独立提交，之后还有发送领取/确认和结算、缓存同步。优先量化/减少桥接往返与提交次数；批量化必须保留 generation fencing、提交后 ACK、幂等及恢复契约。本轮没有擅自改写这些业务事务。
4. **ACK 请求行锁修复仍在**：源码指纹与已验证 V23 JAR 一致；confirm SQL 只更新 dispatch。A 完整 100 条 confirm P95 292ms，B 已记录 1,066 条 P95 150ms（有采集缺口，不外推总体）。必要的因果行锁证明沿用[既有 ACK 锁修复报告](ack-request-lock-fix-20261118.md)：消费者请求行 X 锁仍持有时，ACK 确认已提交。该回归本轮未重新执行。
5. **活动行锁仍保留**：A 从 lockCampaign 返回至主事务提交返回 P95 744ms，是持锁时长下界；lockCampaign SQL 本身包含查询/往返和可能的锁等待，不把整个 elapsed 都叫锁等待。现有 2 秒锁等待采样不是全量锁事件。

已采到的资源峰值（B 为原观测段，缺口不补值）：

| 指标 | A 修正复测 | B 原观测段 |
|---|---:|---:|
| Tomcat busy / max | 91 / 100 | 25 / 100 |
| Tomcat 当前连接峰值 | 140 | 73 |
| Hikari active / pending | 7 / 0 | 6 / 0 |
| JVM heap 使用峰值 | 208.6 MiB | 206.3 MiB |
| 应用 process.cpu.usage 峰值 | 0.102 | 0.097 |

A 正式窗内已记录 GC pause 最大 26ms，不能解释秒级 HTTP 长尾。采样中未看到 Hikari 等待，不能等价为任意瞬间绝无等待。Windows TCP 状态、可用物理内存/提交余量、JVM 线程状态、GC 和 SQL 阶段均留有原始数据；Windows 真实监听队列及完整客户端调度栈不可观测。主机提交上限/可用余量在跑次间自然变化，详见 config/observations，不能把所有差异归因于一个修复。

暂不建议直接采用按活动分区消费：先消除/量化持久化桥接瓶颈，并在可靠的开放式到达模型下重验；当前证据不支持将首要瓶颈归为 MQ 消费并发。

## 7. 测量故障及最小修复

- `flash_acceptance.redis_snapshot()` 原来逐个 reservationId 展开到命令行；B 到约 1,000 个 durable 请求后触发 **WinError 206**，中断了后处理和录制。
- 新增行为测试先 RED：5,000 ID 时命令行长 166,268 字符且没有 stdin；改用 `docker exec -i` + `redis-cli -x`，ID 列表通过标准输入传递，Lua 用局部 `ids` 表恢复列表。查询/不变量/异常处理含义不变。
- 实际 Redis 曾拒绝修改只读 ARGV，已改为局部表；最终真实 3,400 ID 校验 stock=600、processing/invalid/pending/lag/dispatch/cacheJobs 全 0。5,000 ID 单元用例是传输测试，不是 5,000 买家压测。
- B 没有重新发压。保留原始 JTL/JFR/错误日志，恢复一段 JFR并继续最终对账和 3 次重放。提交缺口 2,334 单按缺失保留，不用 paid_at、订单 ID 或估计补齐。细节见 [measurement-recovery.json](jmeter/v23-perf-20261118-234151-B/measurement-recovery.json)。

## 8. 测试、改动与证据

本轮最终脚本回归 **30/30 通过**（原 24 + 新 6）；传输修复的 RED 2 项失败日志和 GREEN 日志均保留。原 V23 功能验证：单元 51 通过/1 可选跳过、真实 MySQL/MQ 等相关 IT 37 通过、8 单行锁/MQ验证，**本轮复用而未重跑 Maven/JUnit**，见[既有回归证据](ack-request-lock-fix-20261118/regression.json)。本轮真实验证为 A/B 的 MySQL、Redis、RocketMQ 生产购买链路及逐项对账，没有用夹具代替。

本轮改动仅测量工具和文档：

- 修改既有 `infra/jmeter/flash_acceptance.py`：标准输入传输；保留其旧同步/库存不变量。
- 新增专用 `run_v23_performance.py`、`v23_plan.py`、`v23-prepare.groovy`、`v23-response.groovy`：同线程预热、固定上限到达率、固定窗口、诊断及有界查询。
- `V23Pacer.java`、`V23Events.java`：精准许可和白名单流式事件导出；`refresh_v23_data.py`：仅隔离测试身份的普通登录刷新；`analyze_v23_performance.py`：离线分布/阶段/资源审计；新增两个测试文件。
- Java 业务实现、SQL 迁移、旧 JMX、旧 runner、既有离线复算算法未改。没有 Git 提交、推送或合并，暂存区未变。

证据入口：

- [v23-perf-20261118-234151/](v23-perf-20261118-234151/)：开始/结束环境、构建指纹、脱敏运行参数、测试日志、计时探针、源文件摘要、私有服务日志引用。
- [A 修正复测](jmeter/v23-perf-20261118-234151-A3/summary.json)：JTL、JMeter/GC 日志、执行命令、JMX、复制的脚本、commit-evidence、observations、reconciliation、replays。
- [B](jmeter/v23-perf-20261118-234151-B/summary.json)：同上，另有两个 Redis 窗口原子快照、两段白名单 JFR 导出及恢复说明。
- 无效准备和 A 初轮也保留在 `jmeter/v23-perf-20261118-234151-A`、`-A1`、`-A2`。
- Token/买家 CSV/原始服务日志/原始 JFR 保留在 `target/v23-perf-20261118-234151/`，不写入公开证据。JTL 只有测试 userId、clientKey、reservationId 等必要关联字段。

## 9. 可复跑方式（本轮不再执行）

先创建新的隔离素材/活动和普通测试身份数据，确认没有历史状态消耗；刷新后每轮立即运行，不复用本轮已消费活动。沿用原 seed/preflight；不能 SkipPreflight。为新跑次选择全新 out/private 路径。

```powershell
# A：独立 warm meta 至少128买家；正式meta为2000买家/库存100
python infra/jmeter/run_v23_performance.py --meta <fresh-A-meta> --warm <fresh-A-warm-meta> --count 2000 --rate 400 --workers 128 --window 0 --out docs/perf-evidence/commerce/jmeter/<new-A-runId> --private target/<new-A-runId>
# B：独立 warm meta 至少64买家；正式meta为4000买家/库存4000
python infra/jmeter/run_v23_performance.py --meta <fresh-B-meta> --warm <fresh-B-warm-meta> --count 3600 --rate 60 --workers 64 --window 60 --out docs/perf-evidence/commerce/jmeter/<new-B-runId> --private target/<new-B-runId>
python -m unittest discover -s infra/jmeter -p 'test_*.py' -v
# 离线复算，不连接中间件
python infra/jmeter/analyze_v23_performance.py <existing-run-directory>
```

下次要获得完整全程 TPS，先确保提交采集和排空观察不被后处理导出错误提前终止，并独立验证 400/s 真实 HTTP 发射能力。本轮只报告有效证据，不能宣称达到 400/s 或通过原 5,000 同步秒杀验收。历史 V19/V22 的 5,000 Barrier 负载、预热和客户端采集开销不同，不给出 V23 同口径性能提升百分比。

支付仍模拟；退款沿用现有模拟流程，本轮未新增退款性能验证。**FFmpeg 容器清理继续标为未验证。**
