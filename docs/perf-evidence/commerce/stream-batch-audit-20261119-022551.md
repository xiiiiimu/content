# Stream 批桥接：前置审计与 RED（2026-11-19）

状态：完成现状审计和两项真实组件行为 RED；尚未实现 GREEN，未运行 batchSize=1/20/57 性能测试。所有既有未提交改动、离线工具和历史跑次保留，没有提交、推送或合并。需要明确永久坏消息处置后继续实施，不自行改变冻结协议。

## 现状与计时

`SaleIngress.drain()` 的 read 上限目前200，但 for 循环每条独立进入 `persist()` 的 `TransactionTemplate`。正常新请求每条 guard（FOR SHARE）、campaign、request、insertRequest、enqueue 共5次SQL，另有一次提交；Redis读/ACK在事务外。ACK发生在该条提交后；读到多条不构成数据库批处理。

复算 V23 A3 的100条完整正式请求：500次SQL、100次提交。每条SQL总耗时 P50/P95/P99=118/467/646ms；提交耗时24/59/153ms；persist根区间192/802/1416ms（包含事务后Redis ACK，不能当作数据库事务耗时）。分位数不能直接相加。证据见 [bridge-batch-20261119-022551-audit/bridge-before-profile.json](bridge-batch-20261119-022551-audit/bridge-before-profile.json)。

`SaleEntryRedis.read()`先XAUTOCLAIM再XREADGROUP，总量有上限、不等满；pending最小空闲30秒。现有恢复持有数据库generation/owner/lease，idle=0接管剩余消息，再按权威状态重建。现有scanner在同一个桥接线程中串行处理消息，另一个线程独立发送MQ。

## 已确认 RED

新增到 `CommerceIngressRevisionIT`，在独立构建目录编译，真实 mysql:8.4 / redis:7.4 / S3mock Testcontainers；没有清空业务数据库。

1. 两条已就绪消息一次drain应只有一个桥接JDBC commit：实际2，期望1。
2. 一批提交后、ACK前崩溃，两条请求和待发记录都应已持久化：实际仅1条，期望2。

实际结果：tests=2、failures=2、errors=0、skipped=0。失败来自尚无原子批事务，不是编译、依赖、Docker或连接故障。明细见 [bridge-batch-20261119-022551-audit/RED-results.json](bridge-batch-20261119-022551-audit/RED-results.json)，原始日志保留 `target/bridge-batch-20261119-022551/RED.log`。

## 尚缺的协议定义

`docs/commerce-ingress-v2.md`第35行明确：

> 此规则只处理旧命名空间导入，新 Stream 请求身份冲突继续 fail closed，不改写事件 payload。

当前冲突审计仅为旧Stream导入定义；新Stream解析失败/身份冲突直接抛错，消息留pending，没有持久化隔离、隔离后ACK、人工重放、无法识别预占的库存释放约定。完整恢复又要求先处理保留Stream，不能静默丢弃坏消息后发布缓存，否则可能丢失未知预占或错误归还库存。

本轮要求“永久错误避免阻塞整批”可以让合法消息独立推进，但坏消息自身是否允许ACK及如何处理不可核实预占，仍需要明确，尤其影响Redis恢复是否可以重新开放。

待选择：

- 保持现有fail-closed：坏消息留pending、不自动释放未知库存；合法消息独立批处理。需要人工纠正的坏消息仍会阻止完整恢复发布。
- 补充持久化隔离协议：审计原payload和canonical首次结果；审计提交后才允许ACK已安全隔离的消息；可核实的孤立预占按generation和幂等释放，无法核实的预占保持隔离并暂停相关入口，另定义受控修复/重放。

在这一选择明确前，未引入新隔离表、ACK坏消息、修改恢复或释放未知库存。

## 后续实施边界

冻结错误路径后：新增配置批上限1/20/57；每次最多读该上限，一批合并合法请求的campaign/请求及clientKey读取、请求插入、dispatch插入，保留唯一约束及并发冲突重新读取；用同一个短事务完成，提交后批量ACK。generation校验和所有写入同事务；恢复owner及租约校验保留。Redis/MQ不移入事务，HTTP仍只做Lua预占+XADD后返回。

先补齐批内重复、canonical冲突、坏消息、generation、提交前/后崩溃、pending接管真实组件测试，再GREEN和相关回归；验证采集可覆盖整个窗口/排空后，才按同一构建逐档运行60/s×60s，前档完全排空后启动后档。统计提交时间不使用paid_at填补。支付/退款仍模拟，FFmpeg容器清理未验证。
