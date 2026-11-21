# 抢购 QPS / 成交 TPS / 延迟：按固定口径复算（离线，2026-11-18）

本轮在既有未提交工作树上只做离线复算：读取已归档 JTL、逐笔数据库对账和 JFR 时间线，没有连接 MySQL/Redis/RocketMQ，没有新发压，没有提交、推送或合并。原跑次文件均未改动。

## 1. 构建与验证状态核对

| 项 | 结果 |
|---|---|
| 分支 / HEAD | `feat/v1.1-material-commerce` / `dd2ab17`，工作区 146 项未提交改动（含历史证据） |
| AGENTS.md | 仓库内未找到（根目录及 3 层子目录） |
| 锁修复源码指纹 | [source-sha256.json](ack-request-lock-fix-20261118/source-sha256.json) 中 14 个文件与当前工作区逐一一致 |
| 独立运行 JAR | `target/commerce-revision-20261118082417/runtime/content-v23-376b7ae3cf20.jar`，SHA-256 复核一致；`src/`、`pom.xml` 无比它更新的文件 |
| 已有回归 | 单元 52（51 过 / 1 跳过）、相关 IT 37 全过、脚本统计 21 项；见 [regression.json](ack-request-lock-fix-20261118/regression.json) |
| 锁修复小规模真实 MQ | 8 单 / 8 权益 / 库存 0，ACK 在请求行 X 锁持有期间已提交；见[锁修复报告](ack-request-lock-fix-20261118.md) |
| 服务 / Stream / MQ / 缓存任务就绪 | **本轮未实测**：执行环境无法访问本机后端与 Docker 中间件，只能引用锁修复报告中的快照（PID 24820 readiness UP，pending/lag/dispatch/缓存任务全 0） |

自锁修复验证后没有新增源码改动，因此本轮不需要补测业务代码；新增的只有离线统计脚本及其单元测试（3 项通过）。

## 2. 固定口径

- **窗口 W** = [首个 POST-purchase sampler 开始, 最后一个 sampler 结束]，取自原始 JTL；脚本断言 JTL 与逐笔时间线的样本数、首尾时间完全一致。
- **抢购接口完成 QPS** = W 内某类响应数 / W 秒。正常业务响应 = 成功受理(200) + 售罄(409 SOLD_OUT) + 其他业务拒绝 + 幂等重放；5xx、读超时、连接超时、连接失败、401 单列。连接超时只归入“连接超时”一类，不与总超时、总连接失败重复相加。
- **订单成交 TPS** = W 内落单事务提交返回的唯一订单数 / W 秒。提交时刻取 JFR 记录的结算主事务提交返回（订单、库存、权益、请求终态、消费回执同一事务）。`paid_at` 只作为记录点，不当提交时刻。另报 **含确认等待的全程 TPS** = 订单数 /（最后提交返回 − W 起点）。
- **端到端延迟** = 受理请求：提交返回 − sampler 开始；最终拒绝：sampler 结束 − 开始。purchase 场景没有轮询，所以受理路径的终点是服务端观测到的提交，不是客户端看到的 SUCCEEDED。
- 不从任何时间中扣除 Groovy、连接或排队时间。分位数用 nearest-rank，位置 ceil(N×p)，不插值。JMeter 与后端 JVM 在同一台 Windows 主机，时钟相同。

## 3. 主要结果

库存 100 的突发跑次里，订单数被库存封顶在 100，所以 W 内的成交 TPS 只反映“100 单分布在多长窗口里”，**不能当作持续成交能力**。最能反映落单链路串行能力的是“首末提交之间的速率”。

| 指标 | c91 正式 PASS | c94 probe | c101 ingress-v2 |
|---|---:|---:|---:|
| 版本 | ingress-v2 之前 | ingress-v2 之前 + 诊断埋点 | V22 ingress-v2（ACK 锁修复前） |
| 同步有效（启动跨度） | 是（3.898s） | 是（2.920s，有 JFR 与线程快照扰动） | **否**（96.134s） |
| 窗口 W | 44.384s | 37.178s | 96.146s |
| 成功受理 / 售罄 / 其他拒绝 / 重放 | 100 / 4900 / 0 / 0 | 100 / 4900 / 0 / 0 | 100 / 4749 / 0 / 0 |
| 5xx / 读超时 / 连接超时 / 连接失败 / 401 | 0 / 0 / 0 / 0 / 0 | 全 0 | 0 / 0 / **151** / 0 / 0 |
| 完成 QPS：正常业务合计 | 112.653 | 134.488 | 50.434 |
| 　其中受理 / 售罄 | 2.253 / 110.400 | 2.690 / 131.798 | 1.040 / 49.394 |
| 　错误合计 | 0 | 0 | 1.571 |
| 单秒正常完成峰值 | 734 | 3153 | 326 |
| 成交 TPS（W 内提交） | **缺证据**：无逐笔提交时刻 | 2.690 | 1.040 |
| 含确认等待的全程 TPS | 缺提交时刻；`paid_at` 记录点 2.494，排空确认上界 2.131 | 3.323 | 1.119 |
| 首末提交间速率 | 缺证据 | 5.05 单/s（10.5→30.1s） | 1.51 单/s（24.0→89.3s） |
| POST 延迟 P50/P95/P99：受理 | 8.444 / 11.516 / 12.130s | 35.749 / 36.703 / 36.951s | 30.295 / 31.640 / 36.947s |
| POST 延迟：售罄 | 25.114 / 41.115 / 43.998s | 35.304 / 36.969 / 37.088s | 0.187 / 59.968 / 65.032s |
| POST 延迟：连接超时 | — | — | 32.577 / 37.960 / 38.595s |
| 端到端：受理到提交 | 缺证据 | 23.701 / 28.945 / 29.457s | 51.088 / 79.106 / 81.317s |
| 端到端：全部最终结果 | 缺证据（受理部分缺） | 35.262 / 36.967 / 37.087s（N=5000） | 0.284 / 60.808 / 65.380s（N=4849，151 笔无最终结果） |
| 最终订单 / 权益 / 重复 / 未终态 | 100 / 100 / 0 / 0 | 100 / 100 / 0 / 0 | 100 / 100 / 0 / 0 |
| 窗口起止 MQ 积压 | 未采集 | 未采集 | 未采集；仅结束快照 dispatch 待发 0、Stream pending/lag 0 |

c92 / c95 / c97 同样做了复算（见 [metrics.json](flash-metrics-recompute-20261118/metrics.json)），但这三轮启动跨度分别为 51.4s / 65.8s / 28.7s，都不是有效的同步负载，所以不列入对比。

## 4. 分段证据（仅受理请求，同一主机时钟）

| 阶段 P50 / P95 / P99（ms） | c94 | c101 |
|---|---|---|
| 服务端接收 → 服务端响应 | 655 / 1087 / 1116 | 1003 / 1151 / 1167 |
| 服务端响应 → JMeter sample 结束 | **25517 / 26706 / 26987** | **28570 / 31004 / 31078** |
| 请求落库 → MQ 发送 | 8927 / 13791 / 14294 | **49630 / 77683 / 79671** |
| MQ 发送 → 开始落单 | 892 / 4069 / 4373 | −24 / 23 / 139 |
| 落单事务（开始 → 提交返回） | 2539 / 4897 / 5168 | 376 / 1059 / 1172 |
| 100 条请求落库跨度 / MQ 发送跨度 | 0.858s / 15.1s | 0.858s / **65.7s** |

数据：[accepted-stage-breakdown.json](flash-metrics-recompute-20261118/accepted-stage-breakdown.json)。

## 5. 已确认的瓶颈

1. **客户端完成观测滞后。** 两轮都是服务端约 1 秒内写完响应，JMeter 却在 25–31 秒后才记录 sample 结束。c94 中受理请求的订单比客户端拿到 POST 响应还早约 11.6 秒（P50）提交。HTTP P95 主要反映这一段，而不是服务端处理。具体是 JVM 调度、安全点、HTTP 客户端读取，还是 TCP 层，仍无法唯一定位。
2. **V22 的 MQ 发送被串行拖慢。** c101 中 100 条请求在 0.86 秒内全部落库，MQ 发送却拉长到 65.7 秒；一旦发出，落单在约 23ms（P95）内开始。延迟集中在发送阶段，与已修复的“ACK 确认等待消费者请求行锁”现象一致。但这一轮没有逐尝试的锁等待记录，**锁等待在其中占多大比例无法量化**。V23 修复后还没有任何规模化跑次。
3. **落单事务本身。** 同一活动的单笔落单事务 P95 约 1.06s（c101）到 4.9s（c94），活动行锁下首末提交间速率为 1.5–5 单/s。这是同一库存行串行的上限，在弄清 V23 发送端改善之前不调线程池。

## 6. 仍缺的证据

- V23（锁修复后）在任何规模下的 QPS/TPS；
- c91 的逐笔提交时刻；
- 客户端实际观测到的 SUCCEEDED 时间（purchase 场景没有轮询）；
- 窗口起止时的 MQ / Stream / dispatch 积压时间序列；
- 逐次发送尝试的耗时与首次重试原因；
- 幂等重放流量（本批每个账号只用一个 clientKey，重放计数 0 是设计使然，不是实测结果）；
- 足量库存下的持续成交数据。

## 7. 下一轮实测（已准备，本轮未执行）

**当前工具有两处限制会影响执行：**

- `-CapacityEvidence` 禁止 warmup；而且 warmup 在独立的 JMeter 进程里跑，会用同一活动消耗库存，**无法预热正式发压 JVM**。
- 限速（`-PurchaseStartsPerSecond`）不能和屏障同时使用。

因此，“预热后 2,000 人 / 5 秒”要真正预热发压 JVM，需要在 JMX 里加一个指向独立预热活动的前置线程组。这个改动未经实跑无法验证，本轮没有改 JMX。在改好之前，下面的 A 只做后端预热（单独一次小跑），属于“发压端冷启动”的对照口径。

```powershell
# PowerShell 7, E:/content, Java 17；后端沿用已运行的 V23 独立 JAR（PID 以实际为准）
$env:JAVA_HOME='C:/Users/西木/.jdks/ms-17.0.17'; $env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$src='target/jmeter-data/meta-finalflash5kn6.json'; $buyers='target/jmeter-data/buyers-p5kstksml9.csv'

# 0) 后端预热：独立活动、50 人，结果不计入
$w=(& infra/jmeter/seed-commerce-data.ps1 -Buyers 50 -Stock 50 -Prefix wu2k -ReuseAuthorMeta $src -ReuseCsvGlob $buyers -ThrottleLimit 8 -ReturnMetaPath | Select-Object -Last 1)
& infra/jmeter/run-commerce-jmeter.ps1 -MetaJson $w -Scenario purchase -Threads 50 -RampSec 1 -Loops 1 -TimeoutMs 30000 -WarmupThreads 0 -CapacityEvidence -SkipHtml -Band warmup2k -RunId ("wu-"+(Get-Date -Format yyyyMMddHHmmss))

# A) 2,000 独立买家，目标 5 秒发完（400/s 限速，无屏障），只测 POST
$a=(& infra/jmeter/seed-commerce-data.ps1 -Buyers 2000 -Stock 100 -Prefix qps2k -ReuseAuthorMeta $src -ReuseCsvGlob $buyers -ThrottleLimit 16 -ReturnMetaPath | Select-Object -Last 1)
$a=(& python infra/jmeter/refresh_commerce_tokens.py $a | Select-Object -Last 1)
& infra/jmeter/run-commerce-jmeter.ps1 -MetaJson $a -Scenario purchase -Threads 2000 -RampSec 1 -Loops 1 -TimeoutMs 30000 -WarmupThreads 0 -PurchaseStartsPerSecond 400 -CapacityEvidence -SkipHtml -Band qps2k -RunId ("qps2k-"+(Get-Date -Format yyyyMMddHHmmss))

# B) 持续成交：库存充足（买家数=库存），例如 60/s × (10s 预热 + 60s 采样) = 4200 人
#    每个账号同素材只能买一单，所以速率上限 ≈ 可用买家数 / 总秒数
$b=(& infra/jmeter/seed-commerce-data.ps1 -Buyers 4200 -Stock 4200 -Prefix tps60 -ReuseAuthorMeta $src -ReuseCsvGlob $buyers -ThrottleLimit 16 -ReturnMetaPath | Select-Object -Last 1)
& infra/jmeter/run-purchase-capacity.ps1 -RunId ("tps60-"+(Get-Date -Format yyyyMMddHHmmss)) -MetaJson $b -Threads 4200 -RampSec 70 -StartsPerSecond 60 -ObservationIntervalSec 5 -DrainTimeoutSec 300 -AllowCapacityLoad
# 结束后：取 [首样本+10s, +70s] 作为固定 60s 窗口；积压取最接近窗口起止的观测样本（±5s，非精确时刻）

# 复算（需要先导出逐笔 request-db 时间线；B 需要服务端 -SegmentTimings 才有提交时刻）
python infra/jmeter/flash_window_metrics.py <inputs.json> <out.json>
```

正式跑次要求：关闭客户端 JFR 和线程快照（不传 `-InjectorJfrSettings`），不加 `-DisableInjectorInlineCaches`，JMX 中的汇总监听器保持 disabled。服务端 `-SegmentTimings` 是 B 拿到逐笔提交时刻的唯一来源，它有开销，需要在报告中注明。

## 8. 本轮文件改动

| 文件 | 说明 |
|---|---|
| `infra/jmeter/flash_window_metrics.py` | 新增：离线固定窗口 QPS/TPS/延迟复算，缺少提交时刻时输出 null，不推算 |
| `infra/jmeter/test_flash_window_metrics.py` | 新增：3 项单元测试（nearest-rank、分类与窗口、缺证据不推算） |
| `docs/perf-evidence/commerce/flash-metrics-recompute-20261118/` | 新增：`inputs.json`、`metrics.json`、`accepted-stage-breakdown.json` |
| `docs/perf-evidence/commerce/flash-metrics-recompute-20261118.md` | 本报告 |
| `docs/perf-evidence/commerce/jmeter/INDEX.md` | 追加索引条目 |

没有修改业务源码、JMX、迁移或任何历史证据。
