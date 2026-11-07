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
- 发送超时是客户端同步 send 参数，并非整个 tick 的绝对墙钟上限；首次路由查询、类加载及连接建立还可能增加耗时。Windows 预检观察到首次连接耗时超过短测试预算，因此预热独立 canary，并为业务租约保留余量。即使真实发送耗时超过租约，数据库 fencing 也拒绝迟到更新。
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
- 集成测试使用 10000ms 租约、3000ms 发送超时、3 次领取、100ms 基础退避、1000ms 退避上限、每轮 1 条；独立 JVM 的 ACK/恢复测试在领取前发送独立 canary，排除首次类加载与连接建立干扰。canary 不使用被测 eventId，不计入业务投递断言。预检允许 10 秒超时，但被测 Relay 始终使用冻结的发送参数。

## 实际执行记录

- RED 提交 `fb64ed0ab9f810ef82bf74567570eff222e5fbde`。Java 17.0.17、Maven 3.9.9 下执行 `mvn -B -ntp -Pintegration -Dit.test=OutboxRelayIT#sendsCommittedEventAndMarksSentOnlyAfterAck verify`（本机额外指定 `-Dmaven.repo.local=E:/content/m2/repository`）。全部测试源码可编译，MySQL 8.4 迁移及 RocketMQ 5.3.2 真发送 SEND_OK、真消费者接收预检成功；目标用例因 RelayRunner 实现缺失而断言失败，1 failure、0 errors、0 skipped。启动冒烟测试通过。
- RED 前的环境调试曾遇到 Broker 就绪竞态和首次客户端连接超时；这些失败没有计为 RED 证据。夹具改为等待 Broker boot success、核对 mqadmin 的成功文本而非仅退出码，并完成真实收发预检后才执行目标断言。
- 本地业务 Topic 已通过 `infra/docker/create-relay-topic.ps1` 显式创建，read/write queues 各 4；原 Broker 配置未修改。打包后的 PropertiesLauncher 运维入口也已验证能到达命令参数校验，缺参以退出码 1 拒绝。
- GREEN 最终本机运行：Java 17.0.17、Maven 3.9.9、Windows 11 / Docker Desktop；`mvn -B -ntp -Pintegration verify`（附本机 repository 路径）BUILD SUCCESS。JUnit 9 个（启动冒烟 1 + Relay 策略 8），真实集成 48 个（迁移 3 + Relay 22 + 发布回归 23），全部 57 个通过，无失败、错误或跳过。未额外宣称执行普通无 profile 的 verify；本次命令本身已执行完整 verify 生命周期。
- 三个独立进程强制终止屏障均有记录：`claim-10001` 为已提交领取后、发送前，恢复 token=2 / attempt=2 后 SENT；`ack-10019` 为真实 SEND_OK 后、SENT 前，终止时仍 RUNNING，恢复进程以 token=2 / attempt=2 重投，消费者确认两条消息的原 eventId、payload、Tag 和属性一致；`last-10005` 为最后一次领取后发送前，过期扫描进入 DEAD，无额外领取或发送。
- `controlledOperatorCommandReplaysWithOsAuditWithoutSending` 用独立 JVM 执行真实运维命令，并故意将环境扫描/生产者标志设为 true；命令强制关闭二者，重放后仍 NEW、有 OS 操作者和原因审计，随后由 Relay 正常发送。审计 SQL 故障另测确认重放整体回滚。
- `expiredUnclaimedTokenCannotCompleteOrRetry`、`reclaimedTokenCannotCompleteOrRetry` 和 DEAD 重放测试覆盖旧 token 在未接管、已接管、人工重放后三种状态下不能覆盖持久化状态。回收器也检查原状态、token 和过期边界。
- 三个非成功 ACK 参数化用例执行真实发送后注入响应码，均不标 SENT，重试保留相同内容。暂停/停止 Broker 用例确认超时/不可用，重连后按真实数据库退避持续重试，在测试上限 3 次以内 SENT；超时后的接收条数只要求至少一条且所有内容一致，不错误要求 exactly-once 或首次重连即成功。
- 最终运行前修正了两个测试问题：过短租约受到 Windows 首次路由/类加载影响；Broker 恢复后首个连接仍可能暂时失败，测试原先错误要求一次重试立即 SENT。修正后完整重跑成功，没有把此前失败的完整运行计为通过。
- 本地证据在 `target/relay-evidence/` 与 `target/surefire-reports/`、`target/failsafe-reports/`；GREEN 哈希、PR 地址及 CI 最终状态见本次交付报告与 Git 历史。

## 数据库与交付边界

V3 追加 `last_attempt_at`、`last_failure_at`、`last_error`、`sent_at` 及两条以 event_type/status 开头的待领取/过期扫描索引；新建 `outbox_replay_audit`，记录 eventId、OS 操作者、原因、旧/新 token、旧 attempt 和数据库时间，约束同一 eventId/new_token 只有一条审计。V1、V2 不改动，eventId/payload 不重写。Flyway 测试覆盖空库、重复迁移、校验、V1 草稿升级及 V2 既有未发送事件升级。

CI 的 Maven job 执行 `mvn --batch-mode --no-transfer-progress -Pintegration verify`，含原发布回归和 Relay MySQL/RocketMQ 故障测试；`always()` 上传 `mysql-rocketmq-test-evidence`，保留 7 天，包含 Surefire/Failsafe 报告、Broker/NameServer 日志及独立 JVM 日志、屏障和强制终止记录。崩溃钩子仅在 src/test，不提供生产故障开关或测试身份接口。

客户端 ACK 枚举与重试参数依据 Apache RocketMQ 5.3.2 官方源码：
[SendStatus](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/producer/SendStatus.java)、
[DefaultMQProducer](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/producer/DefaultMQProducer.java)。
