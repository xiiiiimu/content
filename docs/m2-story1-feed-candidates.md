# M2 Story 1 — Feed 有序候选存储与有界续读

基线为 ADR-001、ADR-002 与 T01/T02；从包含 a71a7d3 的最新 main 开始。只实现 Redis 存储适配器及其测试，不扩展用户关注、MQ 消费/分发、混合归并、签名游标、Feed HTTP 或完整可见性分页。

## 本 Story 契约

- 键为 `feed:v2:{uid}:inbox`，uid 为正 long。ZSET score 恒为 0；member 为 ASCII 13 位 UTC Unix 毫秒、冒号、20 位正 long 帖子 ID，共 34 字节。时间范围 0..9999999999999，ID 范围 1..9223372036854775807。
- 写入前校验整个批次。单批 1..200 条，单次读 1..200 条，避免无界命令和过大返回；200 个正常 member 约 6.8KB。重复写相同 (time,id) 得到相同 member，不增加元素。不可变发布时间由发布权威数据保证，本适配器不查 MySQL、不以单独 Redis ID 映射替代发布契约。
- 首读上界为包含 snapshotMs 的最大合法 ID；续读在 snapshot 上界与传入排他边界中取安全的较小范围。使用 `ZRANGE ... BYLEX REV LIMIT 0 n`，OFFSET 始终为 0，绝不按 score 减一或累计 OFFSET。
- 返回候选列表及最后读取 member（空列表为 null）。这个边界不是签名游标、返回项边界或 ADR-002 的完整 consumedBoundary，不含用户权限、关系版本、候选过滤和多路来源状态。未来归并器不能将预取批次的末尾直接作为客户端已消费位置，必须遵守 ADR-002。成功读取空集合只表示当前这个 Redis 来源范围为空，不证明整个 Feed EXHAUSTED，也不能证明缓存完整或恢复就绪。
- 原子脚本检查键类型及全键 score=0，再执行有界命令。检查非零 score 使用 ZCARD 与 ZCOUNT(0,0)，不扫描或返回全键。读到的 member 在脚本中按固定字节宽度和十进制字符串范围校验，避免 Lua double 损失 BIGINT 精度。
- 发现错误键类型、非零 score、返回批次中非法 member，设置同槽 `feed:v2:{uid}:inbox:untrusted` 标记，记录错误并抛出 `CandidateIndexUnavailableException`。整个批次失败，不返回部分结果、不推进边界。标记不会被正常读写自动清除；权威重建与受控解除属于后续恢复协议。
- Redis 连接/超时等故障也抛上述异常（REDIS_UNAVAILABLE），不能转换为成功空集合。客户端连接由调用者创建、配置有限超时并负责关闭，本 Story 不注册 Spring Redis 自动配置或启动新生产 Worker。客户端依赖使用 Boot BOM 管理的 Lettuce 6.6.0.RELEASE；集成测试用真实 Redis 7.4，2 秒命令超时。
- 不做全键非法 member 预扫描：本次读取实际遇到非法 member 即拒绝；未被扫描的范围不宣称已校验。score 检查覆盖全键。没有实现缓存丢失后的权威恢复、重建 generation 或发布与分发的生产接线。

## RED / GREEN 证据

RED 编译骨架只显式抛出行为未实现异常，不注册生产 Bean。真实 Redis PING/ZSET 预检成功后，目标 T01 用例以断言检查适配器可以执行；以目标行为缺失导致失败，而非编译、依赖或连接故障。

- RED：`90a8cbb22084a070564e7a9dee29c008b03eb488`。Java 17.0.17 / Maven 3.9.9，执行 `mvn -B -ntp -Dtest=ContentApplicationTest -Pintegration -Dit.test=RedisFeedCandidateStoreIT#t01SameMillisecondIdsNineTenElevenOneAtATime verify`，全部源码编译成功，启动冒烟通过，真实 Redis 预检通过，目标用例断言失败（1 failure、0 errors、0 skipped）。异常明确为候选存储行为尚未实现，不以缺少类或连接失败充当 RED。
- GREEN 定向验证：`mvn -B -ntp -Dtest=FeedMemberCodecTest,ContentApplicationTest -Pintegration -Dit.test=RedisFeedCandidateStoreIT verify` BUILD SUCCESS。编码单元 16 + 启动冒烟 1，Redis 集成 18，均无失败、错误或跳过。本机额外使用 `-Dmaven.repo.local=E:/content/m2/repository` 指定缓存。
- Redis 集成覆盖 T01（11→10→9→空来源）、T02（501 条、每次 7 条、预定数值排序逐项比较及严格递减边界）、跨毫秒、快照/排他上界、空来源、重复写入、读者键隔离、合法最大值、非法输入及完整批次验证、200 条单次上限、非法数据持久标记、错误键类型、请求窗口外非零 score、真实暂停超时恢复及关闭连接错误。
- 本轮不改 SQL 表或既有 Flyway 迁移。新增 Lettuce 依赖不引入 Spring Redis 自动配置，默认应用启动和现有发布/Relay 接线不变。
- 完整回归：`mvn -B -ntp -Pintegration verify` BUILD SUCCESS（12:25）。单元 25；真实集成 66（MySQL 迁移 3、发布 23、Relay 22、Redis 18），全部 91 项通过，0 failure / error / skipped。日志为本地 `target/m2-green-final.log`。
- 首次完整回归中，旧 Relay 的恢复 JVM 启动约 112 秒，加独立预热超过 120 秒看门狗；该轮 1 项失败，未计为通过。仅将测试子进程启动/预热等待统一为 180 秒后重新执行上述完整回归；生产租约、发送超时及故障结果断言未改变。
- GREEN 提交紧随本说明；PR 的 CI 执行同一 integration verify，并保留 Redis 容器日志及现有测试报告。远端最终状态以 PR 检查记录为准。

命令依据 [Redis ZRANGE](https://redis.io/docs/latest/commands/zrange/) 与 [ZCOUNT](https://redis.io/docs/latest/commands/zcount/)。本轮不宣称完成 ADR-002 全部 Feed 分页契约或 M2 全阶段。
