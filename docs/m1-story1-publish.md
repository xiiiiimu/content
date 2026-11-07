# M1 Story 1 — 已有文本草稿的幂等发布

本 Story 依据 V2.1 §5、§10 和四份 ADR；V2.1 PDF 保留原样。本文记录用户确认的补充发布协议，不扩大 M0–M9 路线或本轮范围。

## 冻结协议

- `POST /api/posts/{postId}/publish`，必填 `Idempotency-Key`，Body 为 `{"expectedVersion":非负整数}`。正文仅取数据库草稿，不接受正文修改。作者取可信请求 Principal，生产不接受客户端作者头；登录不在本 Story 内。没有 Principal 返回 401。
- 幂等作用域为作者、发布操作、键。请求身份包含操作、postId、expectedVersion。同键同请求返回首次成功响应快照；不同 postId 或 expectedVersion 返回 409 / `IDEMPOTENCY_CONFLICT`。
- 校验身份及所有权，幂等重放先于当前状态和版本检查。非作者返回 403 / `POST_FORBIDDEN`，不存在返回 404 / `POST_NOT_FOUND`。新请求只允许 DRAFT，否则 409 / `POST_STATE_CONFLICT`；版本不匹配为 409 / `POST_VERSION_CONFLICT`。格式错误为 400。键非空、最长 128 字符，不折叠大小写或修剪后存储。
- 首次发布递增版本，固定发布时间、delivery_mode、delivery_epoch。作者行锁串行化发布与切换；publish_seq 在锁内递增，仅用于关注边界，不能代替 epoch 归属。
- 成功请求规范字段与响应快照、帖子更新、作者序号和 Outbox 同一事务提交；任意失败全部回滚，键可重试。数据库唯一约束保护幂等作用域。同一作者先锁作者行，再读取已提交成功记录，因此并发竞争者在锁释放后判断重放或冲突。事务使用 READ COMMITTED，避免不同作者之间因不存在的幂等记录产生范围锁竞争。如出现唯一键异常，先退出并回滚原事务，再开启事务读取已提交幂等记录判断重放或冲突；无对应成功记录则报告持久化失败，不伪造成功。
- `PostPublished` 基础字段为 eventId、postId、authorId、publishedAtMs、deliveryMode、deliveryEpoch、postVersion；按 ADR-003 增补 publishSeq。首次成功响应采用该发布快照，HTTP 200；重放返回相同快照。

## 本轮边界

仅已有文本草稿发布与 Outbox 持久化。不实现草稿创建、登录、Relay、Feed、关注迁移、媒体、热点或 TrendGuard。模式切换只由数据库测试夹具模拟，正式切换属于后续 Story。新增 Flyway 迁移，V1 不变。

## 验证

核心事务、并发、回滚均使用 Testcontainers MySQL 8.4。只有不连接中间件的启动装配冒烟测试提供 Repository Mock；发布验收不使用 Mock 数据库。

- RED：`7bdb02a5ae84fc375af5183a4c2f581d95201e7b`。Java 17 编译全部测试成功，真实 MySQL 8.4 启动并完成 V1 迁移。执行 `mvn -B -ntp -Pintegration -Dit.test=PublishPostIT#publishesDraftAndPersistsMatchingEventAndReceipt verify`，目标断言预期 HTTP 200、实际 404（发布接口未实现），1 个目标测试失败、0 个错误。非编译、依赖、Docker 或连接故障。JUnit 启动冒烟测试 1 个通过。
- GREEN 最终集成运行：Java 17.0.17、Maven 3.9.9、Docker Desktop、MySQL 8.4；`mvn -B -ntp -Pintegration verify` 成功。JUnit 启动冒烟测试 1 个、发布集成测试 23 个、迁移测试 2 个，全部通过；无失败、错误或跳过。本机额外通过 `-Dmaven.repo.local=E:/content/m2/repository` 指定缓存。
- 单独执行普通 `mvn -B -ntp verify` 同样 BUILD SUCCESS，启动冒烟测试 1 个通过；该普通命令不运行集成测试，不能单独作为数据库验收证据。
- 数据库测试包含空库迁移、重复迁移、Flyway validate，以及 V1 既有草稿升级 V2 后仍保留正文、版本和状态。
- 并发请求以屏障启动；模式竞争通过持有真实行锁并观测 `performance_schema.data_lock_waits` 控制顺序，覆盖 PUSH→PULL、PULL→PUSH 的切换先锁与发布先锁。正式切换业务仍未实现，不能据此宣称完整 T04 或 M3 已完成。
- GREEN 调试过程中修正了额外生产触发器需要权限的问题（最终迁移不含触发器），并将二进制键的测试比较改为内容比较；上述最终结果来自修正后的完整重跑。
- CI 已改为 `mvn --batch-mode --no-transfer-progress -Pintegration verify`，确保 PR 执行真实 MySQL 验收，而不只执行启动冒烟测试。GREEN 哈希、PR 地址和 CI 最终状态见本次交付报告及 Git 历史。

后续 Story 的 Relay 需要以本轮 Outbox id 作为稳定 eventId，按租约领取、Broker ACK 后带 token 标记 SENT，覆盖 ACK 后标记前崩溃的同 ID 重投；需预置并实测 Topic 和 Broker 连通性。本轮只记录 NEW 事件，未验证 MQ 投递或 Relay 恢复。部署前仍需后续可信身份适配器提供 Principal，不在本轮增加登录。

## 数据库变更与限制

- V2 新建 `publish_requests`，主键为 `(author_id,operation,request_key)`，保存 postId、expectedVersion 和首次响应 JSON；不引入另一份模式代次字段。
- `posts.publish_request_id` 改为 VARBINARY(512)，与请求表键一致，避免 V1 字符串排序规则忽略尾部空格；接口仍限制 128 个 Java 字符。大小写和尾部空格不同的非空键为不同键。
- Outbox 增加仅用于 PostPublished 的生成列及唯一索引，同一 postId 最多一个发布事件，其他类型事件不受此限制。eventId 使用 Outbox 自增主键；先插入再补齐 payload，二者在同一事务，外部不会读到未完成 payload。
- 已发布字段通过 DRAFT 状态和版本条件更新、先重放后判冲突保证不可变。不使用需要额外服务器权限的生产触发器；直接人工 SQL 修改不属于接口保证。测试故障触发器仅在隔离容器内由测试管理员创建。
- 正式模式切换接口、完整状态机其他转换和登录均未实现。后续实现必须继续使用同一作者行锁并保护已发布时间、归属、序号。
- 本轮回滚使用真实 SQL 故障覆盖作者序号更新、帖子更新、Outbox 插入、payload 更新、幂等记录插入。未执行进程 kill 或提交 ACK 丢失注入，不把这些计为已验证；Relay 可靠性验收留待后续 Story。
