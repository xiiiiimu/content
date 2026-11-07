# M1 Story 1 — 已有文本草稿的幂等发布

本 Story 依据 V2.1 §5、§10 和四份 ADR；V2.1 PDF 保留原样。本文记录用户确认的补充发布协议，不扩大 M0–M9 路线或本轮范围。

## 冻结协议

- `POST /api/posts/{postId}/publish`，必填 `Idempotency-Key`，Body 为 `{"expectedVersion":非负整数}`。正文仅取数据库草稿，不接受正文修改。作者取可信请求 Principal，生产不接受客户端作者头；登录不在本 Story 内。没有 Principal 返回 401。
- 幂等作用域为作者、发布操作、键。请求身份包含操作、postId、expectedVersion。同键同请求返回首次成功响应快照；不同 postId 或 expectedVersion 返回 409 / `IDEMPOTENCY_CONFLICT`。
- 校验身份及所有权，幂等重放先于当前状态和版本检查。非作者返回 403 / `POST_FORBIDDEN`，不存在返回 404 / `POST_NOT_FOUND`。新请求只允许 DRAFT，否则 409 / `POST_STATE_CONFLICT`；版本不匹配为 409 / `POST_VERSION_CONFLICT`。格式错误为 400。键非空、最长 128 字符，不折叠大小写或修剪后存储。
- 首次发布递增版本，固定发布时间、delivery_mode、delivery_epoch。作者行锁串行化发布与切换；publish_seq 在锁内递增，仅用于关注边界，不能代替 epoch 归属。
- 成功请求规范字段与响应快照、帖子更新、作者序号和 Outbox 同一事务提交；任意失败全部回滚，键可重试。数据库唯一约束保护幂等作用域。同一作者先锁作者行，再读取已提交成功记录，因此并发竞争者在锁释放后判断重放或冲突，不在已失败事务内读取。
- `PostPublished` 基础字段为 eventId、postId、authorId、publishedAtMs、deliveryMode、deliveryEpoch、postVersion；按 ADR-003 增补 publishSeq。首次成功响应采用该发布快照，HTTP 200；重放返回相同快照。

## 本轮边界

仅已有文本草稿发布与 Outbox 持久化。不实现草稿创建、登录、Relay、Feed、关注迁移、媒体、热点或 TrendGuard。模式切换只由数据库测试夹具模拟，正式切换属于后续 Story。新增 Flyway 迁移，V1 不变。

## 验证

RED、GREEN 提交及实际执行结果将在完成后记录。核心事务、并发、回滚均使用 Testcontainers MySQL 8.4，禁止用 Mock 代替数据库行为。
