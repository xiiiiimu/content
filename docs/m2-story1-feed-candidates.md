# M2 Story 1 — Feed 有序候选存储与有界续读

基线为 ADR-001、ADR-002 与 T01/T02；从包含 a71a7d3 的最新 main 开始。只实现 Redis 存储适配器及其测试，不扩展用户关注、MQ 消费/分发、混合归并、签名游标、Feed HTTP 或完整可见性分页。

## 本 Story 契约

- 键为 `feed:v2:{uid}:inbox`，uid 为正 long。ZSET score 恒为 0；member 为 ASCII 13 位 UTC Unix 毫秒、冒号、20 位正 long 帖子 ID，共 34 字节。时间范围 0..9999999999999，ID 范围 1..9223372036854775807。
- 写入前校验整个批次。单批 1..200 条，单次读 1..200 条，避免无界命令和过大返回；200 个正常 member 约 6.8KB。重复写相同 (time,id) 得到相同 member，不增加元素。不可变发布时间由发布权威数据保证，本适配器不查 MySQL、不以单独 Redis ID 映射替代发布契约。
- 首读上界为包含 snapshotMs 的最大合法 ID；续读在 snapshot 上界与传入排他边界中取安全的较小范围。使用 `ZRANGE ... BYLEX REV LIMIT 0 n`，OFFSET 始终为 0，绝不按 score 减一或累计 OFFSET。
- 返回候选列表及最后读取 member（空列表为 null）。这个边界不是签名游标、返回项边界或 ADR-002 的完整 consumedBoundary，不含用户权限、关系版本、候选过滤和多路来源状态。成功读取空集合只表示当前这个 Redis 来源范围为空，不证明整个 Feed EXHAUSTED，也不能证明缓存完整或恢复就绪。
- 原子脚本检查键类型及全键 score=0，再执行有界命令。检查非零 score 使用 ZCARD 与 ZCOUNT(0,0)，不扫描或返回全键。读到的 member 在脚本中按固定字节宽度和十进制字符串范围校验，避免 Lua double 损失 BIGINT 精度。
- 发现错误键类型、非零 score、返回批次中非法 member，设置同槽 `feed:v2:{uid}:inbox:untrusted` 标记，记录错误并抛出 `CandidateIndexUnavailableException`。整个批次失败，不返回部分结果、不推进边界。标记不会被正常读写自动清除；权威重建与受控解除属于后续恢复协议。
- Redis 连接/超时等故障也抛上述异常（REDIS_UNAVAILABLE），不能转换为成功空集合。客户端连接由调用者创建、配置有限超时并负责关闭，本 Story 不注册 Spring Redis 自动配置或启动新生产 Worker。客户端依赖使用 Boot BOM 管理的 Lettuce 6.6.0.RELEASE；集成测试用真实 Redis 7.4，2 秒命令超时。
- 不做全键非法 member 预扫描：本次读取实际遇到非法 member 即拒绝；未被扫描的范围不宣称已校验。score 检查覆盖全键。没有实现缓存丢失后的权威恢复、重建 generation 或发布与分发的生产接线。

## RED / GREEN 证据

RED 编译骨架只显式抛出行为未实现异常，不注册生产 Bean。真实 Redis PING/ZSET 预检成功后，目标 T01 用例以断言检查适配器可以执行；以目标行为缺失导致失败，而非编译、依赖或连接故障。实际提交哈希、测试结果与 CI 待执行后记录。

命令依据 [Redis ZRANGE](https://redis.io/docs/latest/commands/zrange/) 与 [ZCOUNT](https://redis.io/docs/latest/commands/zcount/)。本轮不宣称完成 ADR-002 全部 Feed 分页契约或 M2 全阶段。
