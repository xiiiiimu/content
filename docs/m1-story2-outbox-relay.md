# M1 Story 2 — Outbox Relay 可靠投递

基线为 V2.1、四份 ADR、实施计划及 Story 1。保留 M0–M9 路线。本 Story 只发送已提交的 PostPublished；不实现 Inbox、分发任务、Feed、关注迁移、媒体、热点或 TrendGuard。

## 实施前冻结协议

### 连接、消息与 ACK

- 使用经典 remoting Java 客户端 `org.apache.rocketmq:rocketmq-client:5.3.2`，与本地 Broker 5.3.2 对齐，不需要 Proxy/gRPC。Topic `content-post-published-v1`，Tag `PostPublished`，Producer group `content-outbox-relay`，每进程使用独立 instanceName。自动创建 Topic 关闭，运维显式预建 Topic。
- Windows 原生进程连接 NameServer `127.0.0.1:29876`，Broker 广播 `127.0.0.1:30911`；禁用 VIP channel。集成测试使用独立 NameServer/Broker、独立网络和测试 Topic，Broker 主机端口 30921，避免触碰本地业务 Broker。测试仅支持本机 Docker daemon（Windows Docker Desktop / CI Linux），端口占用须明确报错。
- 消息 body 是数据库 payload 原字符串的 UTF-8 字节，不重建、包装或重分配事件。Keys 和 user property `eventId` 均为 Outbox id 的十进制字符串；body.eventId 必须与其一致。重投可能有不同 RocketMQ msgId，但应用 eventId 和 payload 不变。
- 仅同步 send 返回非空 SendResult 且 `SendStatus.SEND_OK` 才判成功 ACK。`FLUSH_DISK_TIMEOUT`、`FLUSH_SLAVE_TIMEOUT`、`SLAVE_NOT_AVAILABLE`、异常和超时均不标 SENT。客户端内部重试次数设为 0，由持久化尝试计数控制重试。
- 原 Broker `ASYNC_MASTER + ASYNC_FLUSH`、单副本、无 slave，配置不修改。SEND_OK 表示 Broker 接收成功，不保证同步 fsync 或复制；不能承诺断电、主机/磁盘丢失后已 SENT 消息不丢失。消费完成也不由 ACK 证明。更强持久性需另行配置及验收，不能静默提升保证。

### 领取、租约和持续发现

- 默认租约 30000ms，发送超时 3000ms，不续租；单次发送为租约留下较大余量。启动时校验租约大于发送超时。
- 每成功领取一次，attempt 加 1、lease_token 加 1，状态为 RUNNING，lease_until = 数据库当前时间 + 租约。领取提交后才发送 MQ；网络发送不在数据库事务内。领取事务使用 READ COMMITTED、`FOR UPDATE SKIP LOCKED`、每次一条，避免批量预占后串行发送造成租约积压。
- NEW 仅在 next_retry_at <= 数据库当前时间时可领取；RUNNING 在 lease_until <= 数据库当前时间时过期。有效租约严格为 lease_until > 数据库当前时间，等于即过期。不用应用时钟作归属判定。
- 默认最多 5 次领取，包含发送前崩溃。退避为 `min(300000,1000 * 2^(attempt-1))` 毫秒，无随机抖动，便于故障验收。有效租约内发送失败：attempt < 5 回 NEW 并安排上述 next_retry_at，否则 DEAD。崩溃后过期：attempt < 5 可接管；attempt >= 5 由扫描转 DEAD，绝不第 6 次领取。过期接管在原租约到期后可立即进行，不另加失败退避，因为原租约已经限制重试频率。
- 单线程固定延迟 1000ms 扫描，每轮最多处理 10 条；持续清理达到上限的过期租约。进程重启后仅依赖数据库恢复，不依赖内存重试队列。单条失败不得永久停止扫描。Relay 默认关闭，须显式启用；未知事件类型保留未发送状态，不误标 SENT。
- 成功、失败、重试、DEAD 条件更新均带 id、状态和 token。发送者更新还要求租约有效；回收器转 DEAD 要求 RUNNING 且租约已过期、attempt 已达上限。更新先锁行，再在新 SQL 语句中计算数据库时间，避免锁等待期间语句起始时间过旧。
- 旧发送者可能迟到发送，至少一次投递允许重复；token 只能阻止其修改持久化状态，不能撤回外部 MQ 副作用。租约失效或被接管后，旧 token 不能标 SENT、安排重试或覆盖新状态。

### 受控人工重放

- 不新增 HTTP 运维接口。采用持有数据库访问权限的运维命令，操作者记录为执行命令的 OS 用户，原因必填且有长度上限；命令权限与数据库凭据是信任边界，不将参数当成远程身份认证。
- 仅允许 DEAD，先锁行。一次事务中 attempt 重置为 0、token 递增、状态改 NEW、lease_until 清空、next_retry_at 设置为数据库当前时间（提交后立即具备新领取资格），并插入审计记录，记录 eventId、操作者、原因、数据库时间、旧/新 token 及旧 attempt。审计失败则全部回滚。
- 人工重放不修改 eventId、event_type、aggregate_id 或 payload。下一次成功领取再次递增 token 并计 attempt=1；任何旧执行者都不能覆盖重放后的状态。

## 故障验收方法与边界

- 真 MySQL + 真 RocketMQ。普通发送、并发领取、数据库失败、过期 fencing、上限和人工重放不使用 Mock。
- 两个崩溃窗口由独立 Java 子进程执行真实领取/发送；测试钩子分别在领取提交后、收到真实 SEND_OK 后发出文件屏障并暂停，由父进程强制终止。恢复用新进程或新 Relay 实例，核验消息 eventId/body、token、attempt 和最终状态。
- 超时通过暂停隔离 Broker 容器制造真实网络等待超时；不可用通过停止隔离 Broker 制造连接故障并随后启动恢复。非成功 ACK 使用客户端 RPCHook 在真实发送完成后替换真实响应的状态码，覆盖三个非 SEND_OK 状态。必须明确区分协议响应注入与实际磁盘/副本故障，不把后者计为已验证。
- 发送 ACK 后数据库更新失败用隔离 MySQL 的测试故障触发器注入；测试管理员创建，生产迁移不要求 SUPER。
- 允许测试缩短租约及退避；控制顺序使用屏障、故障钩子、数据库状态和独立进程，不用任意 sleep 假定顺序。CI 执行完整故障测试并保留报告与子进程日志。

## 实际执行记录

待 RED/GREEN 实测后填写，不将计划写为通过。

客户端 ACK 枚举与重试参数依据 Apache RocketMQ 5.3.2 官方源码：
[SendStatus](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/producer/SendStatus.java)、
[DefaultMQProducer](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/producer/DefaultMQProducer.java)。
