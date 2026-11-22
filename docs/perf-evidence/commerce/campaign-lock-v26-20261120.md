# 活动行锁修复（V26，方案 (b)+(a)）与生产者预热：实现、测试与压测对比（2026-11-20）

方案与契约见 `docs/commerce-campaign-lock-analysis.md`。本轮一次完成：V26 去掉两个活动外键，落单把库存条件 UPDATE 放到最后，对账加孤儿引用检查，RocketMQ 生产者启动预热和派发慢启动。支付与退款仍为模拟实现。

## 1. 结论

1. **活动行锁的排队消除了，但瓶颈转移到了 MQ 派发，吞吐没有提高。** B 档活动行锁等待从 26,783 次降到 20–37 次，落单事务（从开始到主提交返回）P50 从 1,681 ms 降到 188–212 ms。但积压从 Redis Stream 前移到 MySQL：PROCESSING 峰值从 65 升到 2,308–2,479，派发表 PENDING 峰值从 51 升到 2,305–2,477。B 档（派发并发 1）窗口内只派发了 2.8–3.4 单/秒，全程 TPS 7.05–8.19，比修复前的 10.02 低。
2. **并发 2 和 C 档（并发 4）把派发提了上来：** 窗口后派发分别为 12.2/s 和 17.2/s，全程 TPS 分别为 10.00 和 10.95，请求到提交 P95 分别为 247 s 和 223 s（修复前 B/C 为 278/265 s），排空时间分别为 228 s 和 211 s（修复前 292/281 s）。
3. **C 档里活动行重新出现等待（6,318 次）。** 现在持锁区间只剩"最后的 UPDATE → 提交"，但这一段在负载下平均 90 ms，因此单活动上限仍约 11–13 单/秒（实测 10 秒窗口峰值 17.7 单/秒）。也就是说，限制已经从"锁里有多少条语句"变成了"负载下一次提交要多久"。
4. **HTTP 侧变慢了：** 所有 V26 运行中 Redis 预留脚本 P95 从 32 ms 升到 149 ms，HTTP P95 从约 45 ms 升到约 200 ms，注入速率只有 47–52/s（目标 60/s）。原因**缺证据**，见 §5。因此窗口内 TPS 与修复前不能直接比较。
5. **生产者预热生效：** 启动预热 2.3–3.7 s，所有 V26 运行中发送失败 0 次，租约使用最大 1.3–3.1 s；修复前首批发送曾用到 11–25 s。
6. **正确性：** 每一档 `correctnessPass` 都为真，孤儿引用都为 0，未出现租约违规或系统错误。

## 2. 实现

| 改动 | 文件 |
|---|---|
| V26：删除 `fk_purchase_campaign`、`fk_order_campaign`，保留 `ix_purchase_campaign_state`、`fk_order_campaign` 两个索引 | `src/main/resources/db/migration/V26__drop_campaign_hot_row_fks.sql` |
| 落单：不加锁读活动；先写订单、权益、请求终态、回执和脏标记，最后执行条件 UPDATE（`state='OPEN' AND start_ms<=at AND end_ms>=at AND sold_count<stock_cap`）。影响 0 行时抛出异常回滚，再在新事务里锁请求、按已提交的活动行重判 CAMPAIGN_CLOSED / NOT_STARTED / ENDED / SOLD_OUT 并拒绝；若重判时库存又够了（期间有退款），重试一次，仍不成则返回 503，由 MQ 重投。提前拒绝的判断顺序与改动前一致。退款路径不变。 | `SaleService.java`、`SaleMapper.java` |
| 暂停点 `settle.beforeStockUpdate`（测试和演示用，受 fault-hooks 开关控制） | `SaleFaultHooks.java` |
| 生产者预热：先解析 topic 路由（不存在时用默认路由），再对所有 Broker 发心跳并确认每个队列都有 Broker 地址，成功后才把发送器交给派发；最多阻塞启动 60 s（`content.sale.producer-warmup-deadline-ms`），超时后后台每 1 s（`...-retry-ms`）重试，期间派发视为"没有发送器"，行保持 PENDING。每次尝试的耗时和异常类型都写日志。 | `SaleProducerWarmup.java`、`SaleConfiguration.java` |
| 派发慢启动：成功发送满 N 次前，每次只领 1 行、占 1 个槽（默认 `content.sale.dispatch-slow-start-sends=4`）；失败的发送不计数。发送失败按异常类型计数（`sendFailureTypes()`），并记录类型、是否已 ACK、耗时。 | `SaleDispatcher.java` |
| 对账：孤儿引用检查（请求无活动、订单无活动、订单与请求的活动或帖子不一致），纳入 `checks.noOrphanReferences` 和对照脚本的必过项 | `infra/jmeter/run_v23_performance.py`、`run_dispatch_comparison.py` |
| 工具：并发 2 档 `B2=(batch,20,2)`；模式检查改为 V26；每单提交次数分项工具；锁持有指标同时识别 `lockCampaign`（V25）和 `tryIncrementSold`（V26）；回归批次加入锁契约和下线可见性 | `run_dispatch_comparison.py`、`commits_per_order.py`（新）、`analyze_v23_performance.py`、`run_dispatch_regression.py` |

## 3. 测试（先 RED 后 GREEN）

证据目录 `target/campaign-lock-20261120-001706/`（私有）。

| 测试 | 内容 | RED | GREEN |
|---|---|---|---|
| `CommerceCampaignLockIT#lock*`（新，5 个） | 落单暂停在最后 UPDATE 之前时：活动行可 `FOR UPDATE NOWAIT`，bridge 式插入不等待，另一买家的落单可完成；期间关闭活动 → `REJECTED:CAMPAIGN_CLOSED`，订单和权益都回滚；期间售罄 → `REJECTED:SOLD_OUT`；期间下架不死锁；期间退款完成，`sold_count` 等于 PAID 订单数 | 5 个都因目标行为缺失而失败；下架用例拿到真实的 `DeadlockLoserDataAccessException` | 通过 |
| `MySqlMigrationIT`（3 个） | 迁移数改为 26/25/24；V26 后两个外键不存在、`fk_sale_elig_campaign` 仍在、两个索引保留 | 失败（期望 26 实得 25） | 通过 |
| `CommerceDispatchBatchIT#batchSlowStart*`、`#batchSendFailure*`（新，3 个） | 慢启动先领 1 行；失败的发送不推进慢启动；失败按异常类型记录 | 失败 | 通过 |
| `SaleProducerWarmupTest`（新，3 个） | 路由和 Broker 都成功后才安装发送器且只安装一次；记录每次尝试；超时后后台重试并安装 | 失败 | 通过 |
| `test_orphan_references.py`（新，2 个） | 三项计数与 SQL 涉及的表 | 失败（骨架返回 0） | 通过 |
| `SaleDispatchDefaultsTest` | 增加慢启动默认值 4 的断言 | — | 通过 |

RED 日志：`RED-1.log`（第一次运行中有 2 个锁用例在继承的 `wipe` 里报 FK 错误，属于夹具问题，不算 RED，日志保留）、`RED-2-lock.log`（修夹具后 5 个用例都在目标断言处失败）、`RED-3-dispatch.log`、`RED-python.log`。夹具修复只是让 `@AfterEach` 等异步任务结束（最多 60 s），没有放宽任何断言或超时。

GREEN 和回归：
- `GREEN-1.log`：锁契约 5、迁移 3、预热 3、默认值 3，全过。
- `unit-suite.log`：surefire 58 个通过，1 个跳过（原有）。
- `python-suite-root.log`：38 个通过。`python-suite.log` 里有 1 个错误，是在 `infra/jmeter` 目录下运行时相对路径找不到 jmx，与本次改动无关。
- 分批回归（failsafe `-Xmx1g`，每批前过资源关卡）：`regression/` 跑了 b1–b4（16+2+36+38），b5 前关卡测到可用内存 3,060 MiB，低于 3,072 MiB，按规则停止（记录在 `regression-gate-stop.json`，不是因内存被杀，门槛未改）。之后用 `--from-batch b5-purchase` 在新目录 `regression-from-b5/` 跑完 b5–b8（9+2+5+1）。8 批合计 109 个，全部通过。

重跑命令：

```
python infra/jmeter/run_dispatch_regression.py --out target/<新目录>/regression
mvn -B -Pintegration verify -Dtest=SaleProducerWarmupTest,SaleDispatchDefaultsTest -Dsurefire.failIfNoSpecifiedTests=false "-Dit.test=CommerceCampaignLockIT#lock*,MySqlMigrationIT" -Dfailsafe.failIfNoSpecifiedTests=false "-DargLine=--add-opens=java.base/sun.nio.ch=ALL-UNNAMED -Xmx1g"
python infra/jmeter/run_dispatch_comparison.py --jar <jar> --private target/<新目录> --public docs/perf-evidence/commerce/jmeter/<新目录> --classpath <runtime-classpath.txt> --profiles B B2 C --skip-smoke
python infra/jmeter/commits_per_order.py <测量目录> --out <新文件>
```

## 4. 压测

同一 JAR：`content-v26-6924c4880b0d.jar`（SHA-256 `6924c4880b0de067…`，清单 `target/campaign-lock-20261120-001706/runtime/build-manifest.json`）。每档：资源关卡、新活动、新 clientKey、60/s × 60 s、bridge 批量 20、20 个消费线程，其他设置不变。

- 单跑 B（含冒烟）：`docs/perf-evidence/commerce/jmeter/campaign-lock-20261120-023541/`
- B、B2：`campaign-lock-tiers-20261120-025631/`。C 开始前关卡失败：Docker 对 Broker 的健康检查（每 10 s 起一个 `mqadmin` JVM，超时 8 s）连续 19 次超时；同一关卡里的 3 次 Broker 探测都成功。记录在 `sequence-failure.json`，未降低门槛。
- C：`campaign-lock-c-20261120-032720/`，Broker 恢复健康、关卡通过后单独运行。
- 修复前对照：`mq-dispatch-20261119-185405/B`、`/C`、`mq-dispatch-20261119-122940/A`。

| 指标 | 修复前 B | 修复前 C | V26 B（单跑） | V26 B | V26 B2（并发 2） | V26 C（并发 4） |
|---|---|---|---|---|---|---|
| 样本 / 注入速率 (/s) | 3499 / 58.3 | 3482 / 58.0 | 3138 / 52.3 | 2895 / 48.3 | 2849 / 47.5 | 2923 / 48.7 |
| HTTP P95 / P99 (ms) | 44 / 88 | 46 / 88 | 204 / 338 | 196 / 284 | 237 / 399 | 244 / 476 |
| 窗口 TPS | 8.05 | 7.40 | 3.38 | 2.73 | 4.48 | 4.67 |
| 全程 TPS | 10.02 | 10.33 | 8.19 | 7.05 | 10.00 | **10.95** |
| 请求→提交 P50 / P95 (s) | 152.9 / 278.0 | 148.8 / 265.3 | 195.5 / 339.4 | 211.1 / 367.3 | 133.3 / 247.2 | **129.7 / 222.8** |
| 排空 (s) | 291.9 | 280.5 | 326.3 | 354.5 | 228.2 | **210.5** |
| 窗口内 / 窗口后派发 (单/秒) | 8.7 / 10.4 | 7.8 / 11.1 | 3.4 / 9.3 | 2.8 / 8.1 | 4.6 / 12.2 | 7.2 / **17.2** |
| PROCESSING 峰值 | 65 | 38 | 2479 | 2308 | 1497 | 1033 |
| 派发 PENDING 峰值 | 51 | 20 | 2477 | 2305 | 1492 | 262 |
| Redis Stream 积压峰值 | 2951 | 2990 | 2077 | 1945 | 2149 | 2277 |
| 活动行锁等待（采样次数） | 26,783 | 23,456 | 37 | 20 | 202 | 6,318 |
| 落单事务开始→主提交 P50 / P95 (ms) | 1681 / 2612 | 1553 / 2505 | 188 / 547 | 212 / 587 | 370 / 1038 | 1119 / 2992 |
| 取得活动行锁后→提交结束，均值 (ms) | 99.5 | 96.7 | — | — | 77.1 | 90.2 |
| 落单提交 P50 / P95 (ms) | 30 / 55 | 28 / 50 | 36 / 116 | 38 / 138 | 50 / 234 | 63 / 256 |
| 租约使用 P95 / 最大 (ms) | 336 / 15,634 | 421 / 24,945 | 580 / 1,339 | 661 / 1,598 | 905 / 2,541 | 1,126 / 3,072 |
| 发送失败 | 1 | 8 | 0 | 0 | 0 | 0 |
| 孤儿引用 | — | — | 0 | 0 | 0 | 0 |

说明：
- 修复前"取得锁后→提交"约 97–100 ms，其中提交只占约 33 ms，其余是锁内的订单写入；V26 后这一段只剩提交本身，但负载下提交均值涨到 77–90 ms，所以单活动上限仍是 11–13/s。
- V26 窗口 TPS 偏低有两个原因：一是派发跟不上 bridge 涌入 MySQL 的速度（B 档最明显）；二是 §5 的 HTTP/Redis 变慢导致注入只有 47–52/s。窗口 TPS 不适合与修复前直接比较；全程 TPS、请求→提交、排空时间更能说明问题。

### 4.1 每笔订单的平均提交次数（全链路，`commits_per_order.py`）

订单数 = 包含 `insertOrder` 且提交成功的落单事务数（含预热订单）。修复前三档用同一工具重算，与之前手算结果一致（6.23 / 4.51 / 4.33）。

| 分项 | 修复前 A | 修复前 B | 修复前 C | V26 B（单跑） | V26 B | V26 B2 | V26 C |
|---|---|---|---|---|---|---|---|
| 桥接（Stream→MySQL 落请求） | 0.051 | 0.051 | 0.051 | 0.051 | 0.051 | 0.051 | 0.051 |
| 发送：领取 | 2.08（串行，领取+确认） | 0.798 | 0.975 | 0.593 | 0.589 | 0.340 | 0.480 |
| 发送：确认 | — | 0.501 | 0.150 | 0.501 | 0.501 | 0.257 | 0.252 |
| 落单 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| 缓存同步：落单后的两次 | 2.000 | 2.000 | 2.000 | 2.000 | 2.000 | 2.000 | 2.000 |
| 缓存同步：bridge 触发 | 1.097 | 0.161 | 0.157 | 0.919 | 0.953 | 0.677 | 0.580 |
| **合计** | **6.23** | **4.51** | **4.33** | **5.06** | **5.09** | **4.33** | **4.36** |
| 回滚次数 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 全系统平均提交/秒（记录区间） | — | — | — | 36.5 | 31.7 | 37.2 | 40.9 |

- 本次压测中库存足够，所以没有出现"最后 UPDATE 影响 0 行"的回滚；这条路径由 `lockClose*`、`lockSoldOut*` 两个契约测试覆盖。
- bridge 触发的缓存同步从 0.16 升到 0.58–0.95：bridge 不再被活动行卡住，每轮处理更少、轮次更多。

### 4.2 本机落单上限估算

两个约束，取较小者：

1. **单活动热点行**：持锁区间是"最后的 UPDATE → 提交结束"。
   - 空闲时这一段约 16–17 ms（基线：单连接同一行，单事务 P50 16.5–17.5 ms），对应约 **55–60 单/秒**。
   - 本轮负载下实测均值 77–90 ms，对应 **11–13 单/秒**；C 档 10 秒窗口峰值 17.7 单/秒。
   - 所以单活动的实际上限由"负载下一次提交要多久"决定，本机在当前负载下约 11–18 单/秒。
2. **全系统提交预算**：空闲基线 16 连接合计 307–353 次提交/秒（三轮，最近一次 2026-11-20 03:41 为 353，环境未退化）。
   - 按 V26 并发档每单 4.33–4.36 次提交，提交预算对应约 **70–80 单/秒**（多个活动并行时）。
   - 若按 §6 的建议把缓存同步降到每单约 1 次以内，每单约 2.3–2.6 次提交，对应约 **120–150 单/秒**。
   - 本轮实际只用到约 40 次提交/秒，离预算很远；负载下的提交变慢不是刷盘饱和造成的，原因见 §5（缺证据）。

结论：在本机环境，单活动约 11–18 单/秒（负载下），理论上可到约 55–60 单/秒（提交时间回到空闲水平时）；全系统按提交预算约 70–80 单/秒。负载下提交和 Redis 都比空闲时慢 2–5 倍，这部分没有直接证据，上限估算按实测的负载下提交时间给出，空闲上限只作对照。

## 5. 未解释：V26 运行中 Redis 和提交在负载下变慢（缺证据）

- HTTP 新增延迟全部在 `redis.reserve.lua`（P95 32 → 149 ms）。Redis 慢日志里，C 档 60 秒窗口内预留脚本本身有 15 次超过 10 ms（最长 203 ms），单个 `HGET` 也有 203 ms 的情况，而这个脚本只做 O(1) 的 HGET、`INFO server` 和 `XADD`。
- 排除项：AOF `aof_delayed_fsync=0`，最近一次 fork 4 ms；慢日志中 38–170 ms 的 `local ids=` 脚本来自测试工具 `flash_acceptance.py` 的排空对账，只在窗口外出现；空闲时 MySQL 提交基线没有变差（03:41 重测：16 连接 353 次/秒，P50 39 ms）。
- 待验证的两种解释：(1) 代码效应：bridge 不再被卡住后，窗口内 MySQL 写入和 Redis 访问更密集，与 HTTP 争用同一个 WSL 虚拟机的 CPU；(2) 环境效应：同一时段 Broker 容器 CPU 曾到 176%（其健康检查每 10 s 起一个 `mqadmin` JVM），Docker 健康检查连续超时。窗口内没有按容器采样 CPU，所以无法区分。
- 建议的对照：在当前环境下用修复前的 V25 JAR 跑一档 B（约 10 分钟），同时按秒采样 `docker stats`。若 V25 也出现 Redis 变慢，就是环境问题；否则是代码效应。**本轮未执行**，等确认。

## 6. 缓存同步（每单约 2.6–3.0 次提交）：优化建议，本轮不实现

1. 落单后的两次 `bestEffort`（REQUEST、OWNER）各自开一个事务。合并为一次（同一事务里 `job` + Redis 写 + `applied`），每单减少 1 次提交。
2. 进一步：把 `applied` 标记改为批量，由后台每 N 毫秒对一批已应用的任务做一次 UPDATE；落单线程只写 Redis。每单的同步提交可降到约 0.1。
3. bridge 触发的同步（0.58–0.95）：在 bridge 的批次里合并，与 `persist-batch` 同周期一次提交；或者设一个最小批量或时间窗口，避免 bridge 轮次变多后同步次数随之上升。
4. 预期：每单从 4.33–4.36 降到约 2.3–2.6 次提交，对应全系统提交预算约 120–150 单/秒（§4.2）。这些改动都影响缓存与数据库的一致性窗口，需要按 RED→GREEN 单独做。

## 7. 进程与私有证据

- 修复前留下的 C 档后端 PID 49824（旧 JAR）：日志、访问日志、`safe-runtime.json` 已复制到 `target/mq-dispatch-20261119-083643/perf-private-20261119-185405/backend-C/snapshot-before-stop-20261120/`；它没有在跑的 JFR 录制。2026-11-20 01:47:17 停止（`stop.json`）。
- 压测脚本按规则只替换同一 JAR 启动的后端，日志都留在各自的诊断目录：冒烟 PID 920 → 单跑 B PID 50048 → B PID 11880 → B2 PID 19680 → C PID 3112。
- **当前仍在运行：C 档后端 PID 3112**（V26 JAR，空闲），日志在 `target/campaign-lock-20261120-001706/perf-private-c-20261120-032720/backend-C/`。
- 私有数据（Token、CSV、meta、JFR）都在 `target/campaign-lock-20261120-001706/` 下，没有进入 `docs/`。
- 未提交、未推送。

## 8. 追加（2026-11-20 05:18）：同一 V26 JAR 在调整后环境下重跑 B，判断 §5 是否为环境问题

**做法**：唯一变量是环境；JAR（`content-v26-6924c4880b0d`）、配置、数据规模和流程都与 §4 的 V26 B 相同（资源关卡、新活动、新 clientKey、60/s × 60 s、不跑冒烟）。按约定先跑 B：若 HTTP P95、Redis 脚本耗时、到达率都恢复，就判定为环境问题并继续跑 C；否则停下讨论。**B 未恢复，因此 C 没有跑。**

证据：公开 `docs/perf-evidence/commerce/jmeter/campaign-lock-env-20261120-051830-B/`（含 `env-sample-report.json`、`environment-notes.json`）；私有 `target/campaign-lock-env-20261120-051830/`（按秒原始采样 `env-B/*.ndjson`、后端日志）。采样工具：`infra/jmeter/env_sampler.py`、`env_sample_report.py`（新）。

### 8.1 环境差异（与用户说明对照）

- 用户说明：前几轮压测时笔记本可能在用电池、CPU 降频。本轮已接电源并设为最佳性能，重启过电脑，WSL 内存上限设为 5 GB，停止了 Ubuntu 发行版，释放了内存。**前几轮的电源状态没有记录，缺证据。**
- 本轮实测：
  - 交流供电（`Win32_Battery.BatteryStatus=2`），电源计划为"高性能"；Ubuntu 发行版已停止；开始前可用内存 3,370 MiB。
  - **WSL 内存上限没有生效**：配置写在 `C:\Users\<用户>\.wslconfig.txt`（内容是 `memory=5GB`），WSL 只读取 `.wslconfig`。Docker 仍报 7.6 GiB，`vmmemWSL` 占 4.9 GB。
  - `LastBootUpTime` 仍是 2026-11-16 16:10；04:47 的启动事件（Kernel-Boot 27）显示这次是快速启动，不是完整重启。
- 采样本身的开销：pwsh 加 WmiPrvSE 约 0.25 个核。§4 的运行没有这项采样。窗口内主机采样实际间隔约 1.5 s（60 秒窗口内 41 条），docker stats 和 Redis 采样按 1 s。

### 8.2 结果

| 指标 | V25 B（修复前，185405） | V26 B（§4 单跑） | V26 B（§4 三档中的 B） | **V26 B（本轮环境）** |
|---|---|---|---|---|
| 样本 / 注入速率 (/s) | 3499 / 58.3 | 3138 / 52.3 | 2895 / 48.3 | **3392 / 56.5** |
| HTTP P95 / P99 (ms) | 44 / 88 | 204 / 338 | 196 / 284 | **179 / 323** |
| `redis.reserve.lua`（客户端计时）P50 / P95 / P99 (ms) | 5 / 32 / 73 | — | 23 / 149 / 246 | **15 / 155 / 303** |
| 采样器发起→进入应用 P95 (ms) | 7 | — | 40 | **15** |
| 窗口 TPS / 全程 TPS | 8.05 / 10.02 | 3.38 / 8.19 | 2.73 / 7.05 | **4.53 / 8.24** |
| 请求→提交 P95 (s) / 排空 (s) | 278 / 292 | 339 / 326 | 367 / 355 | **365 / 355** |
| PROCESSING 峰值 / 派发 PENDING 峰值 | 65 / 51 | 2479 / 2477 | 2308 / 2305 | **2674 / 2670** |
| 落单提交 P50 / P95 (ms) | 30 / 55 | 36 / 116 | 38 / 138 | **36 / 98** |
| 每单提交次数 | 4.51 | 5.06 | 5.09 | **5.11** |
| 正确性 / 孤儿引用 / 发送失败 | 通过 / — / 1 | 通过 / 0 / 0 | 通过 / 0 / 0 | **通过 / 0 / 0** |

判定：到达率基本恢复（56.5/s，修复前 58.3/s），进入应用前的排队也下降了（P95 40 → 15 ms）；但 **HTTP P95（179 ms）和 Redis 预占脚本 P95（155 ms）没有恢复**，仍是修复前的 4–5 倍。按约定停在这里。

### 8.3 按秒采样（60 秒窗口内；核数 = 进程 CPU ÷ 100%）

| 项 | P50 | P95 | 最大 |
|---|---|---|---|
| 整机 CPU (%) | 64 | 79 | 83 |
| 有效频率 (MHz) | 1,317 | 1,418 | 1,620 |
| 占最大频率比例 (%) | 74 | 77 | 79 |
| 后端 JVM（核） | 1.16 | 3.16 | 3.59 |
| JMeter（核） | 0.98 | 2.02 | 2.27 |
| vmmem，即 Docker 虚拟机（核） | 3.99 | 6.45 | 7.61 |
| 容器 Broker CPU (%) | 241 | 611 | 814 |
| 容器 MySQL CPU (%) | 165 | 260 | 373 |
| 容器 Redis CPU (%) | 40 | 115 | 201 |
| Redis 服务端脚本耗时（每次调用，μs） | 731 | 3,858 | 5,650 |
| Redis 每秒脚本调用 / 每秒命令数 | 68 / 1,865 | 79 / 2,016 | 86 / 3,842 |

窗口内 SLOWLOG（≥10 ms）：预占脚本 66 次（P50 19.7 ms，最长 200 ms）；普通 `HGET` 39 次（P50 19.1 ms，最长 200 ms）；`HSET` 13 次；`HINCRBY` 4 次。窗口前后作为对照：窗口前 Broker 容器 CPU P50 为 40%；压测结束后空闲时 Broker 为 6–60%，MySQL 为 27–37%。

### 8.4 解读

1. **CPU 频率在负载下明显受限。** 接电源、高性能计划下，负载期间有效频率 P50 仍只有约 1.3 GHz，占最大频率比例始终不超过 80%（i7-1260P，16 个逻辑核）；空闲时测到过 2.2–2.8 GHz。这说明频率被功耗或温度上限压住了，"接电源"并没有解除这个限制。
2. **Redis 的慢来自线程拿不到 CPU，而不是脚本本身。** 一个 O(1) 的 `HGET` 在服务端用了 200 ms，而同一秒的脚本平均每次只要 0.7–3.9 ms，说明 Redis 主线程在执行中被挂起了；AOF 延迟写盘为 0（§5）。这与 Docker 虚拟机占用 4–7.6 个核、频率降到 1.3 GHz 一致。
3. **Broker 容器的 CPU 与负载不成比例。** 窗口内只派发了约 4.6 条/秒，Broker 却用了 2.4 个核（P50），最高 8.1 个核，空闲时降回 0.06–0.6 个核。原因**缺证据**。已知的一个因素：它的 Docker 健康检查每 10 秒起一个 `mqadmin` JVM，CPU 紧张时启动更慢、可能重叠；但这不足以解释 2–8 个核。
4. **结论：不能判定为单纯的环境问题。** 环境确实受限（频率受限、WSL 上限未生效），到达率随环境改善而恢复；但 HTTP P95 和 Redis 脚本耗时在同样的环境改善下没有恢复。V26 在窗口内对 MySQL（165%）和 Broker 施加的负载，是否就是挤占 Redis CPU 的来源，需要一个在同一环境下的修复前对照才能回答，即用独立数据库跑 V25。**本轮未执行，等确认。**
5. 运行中的后端：B 档 PID 7876（V26 JAR，空闲），日志在 `target/campaign-lock-env-20261120-051830/perf-private-B/backend-B/`。
6. 缓存同步优化仍只列建议（§6），没有实现。
