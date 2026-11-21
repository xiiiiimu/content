# 秒杀同步压测补充契约（2026-11-18，正式复验前冻结）

保留原屏障要求：全量不同买家、每人一次、库存 100；RampSec=1、Loops=1、WarmupThreads=0、PurchaseStartsPerSecond=0、TimeoutMs=30000、BarrierTimeoutMs=120000。屏障必须 readyCount=配置人数且 timedOut=false，不允许分组或限速替代。

增加工具质量检查：JTL 首末请求启动跨度必须不超过 **5000ms**。这是为否决已发现的 112.8 秒客户端发放拖尾而加严的测量门槛，不是业务延迟 SLA；不替代屏障检查。时间戳强制使用 sampler start，不能宣称等价网卡发包时间。失败轮次原证据不改写，旧报告缺少此门槛的 PASS 必须重新解释。

必须全样本、全唯一用户，并得到恰好 100 个属于本活动的接受响应、100 笔 PAID、100 份有效购买权益，MySQL/Redis 库存均为 0；无重复订单/权益、无遗留 PROCESSING/HELD。其他请求只能是带真实 SOLD_OUT 错误码的 HTTP 409。401、5xx、连接错误、超时、未知拒绝、对账不一致均失败。HTTP 200 是接受，不是支付。

所有重试复验都创建新帖子、新活动、新 CSV、活动隔离 clientKey。允许复用已授权的 jma/jmb 测试身份、既有关注关系并重新登录，购买仍执行原权限检查。不得复用已售活动，不清空业务库。

发压器固定 Java 17，使用现有 JMeter 5.6.3 / Groovy 3.0.20。启用 groovy.target.indy=true，避免已观测到的并发 Groovy 调用点类生成风暴。实际堆为 Xms512m/Xmx3072m，栈 Xss256k，以进程日志/屏障元数据为准；不修改本机 setenv.bat。相关说明见 [Groovy 官方文档](https://www.groovy-lang.org/indy.html)。

请求启动 QPS=样本数/首末启动跨度；HTTP 完成吞吐=HTTP 响应数/首请求启动至最后样本完成；HTTP200 QPS 使用同一完成窗口。最终成交 TPS=唯一 PAID/首请求至最后 paid_at 记录时间；paid_at 不是事务提交时刻。排空使用完成后观测确认的上界，并保留逐笔数据库/Redis 对账。JMX 的连接和响应超时各 30 秒，不是总 wall-clock 的 30 秒硬截止。

只验证此本地购买场景，不外推持续 5000 TPS、5000 笔成交或生产容量。
