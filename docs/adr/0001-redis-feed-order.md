# ADR-001 Redis Feed 排序与续读

状态：已采纳，待实现与验收。基线：SDD V2.1 §4。本 ADR 替换该节的 Redis 编码和查询细节。

## 决策

使用独立版本键 `feed:v2:{uid}:inbox`。所有 member 的 score 固定为 0。member 为 ASCII `{publishedAtMs:013d}:{postId:020d}`，长度 34。时间为 UTC Unix 毫秒，范围 0 至 9999999999999。ID 为 1 至 9223372036854775807。使用整数解析、固定宽度与逐字节比较。发帖时间一经发布固定，同一帖子 ID 不重新发布，重新发布创建新 ID。由此，同一帖子对同一 inbox 重放产生相同 member，SQL 与 Redis 的降序一致。

- 写入：`ZADD key 0 member`。所有批次命令都成功才允许提交进度。使用有界批次。
- 首次读取：`ZRANGE key [<snapshotMs>:09223372036854775807 - BYLEX REV LIMIT 0 n`，其中 snapshotMs 已补齐 13 位。
- 续读：`ZRANGE key (<boundaryMember> - BYLEX REV LIMIT 0 n`，boundaryMember 取全局和来源排他边界中较小者。禁止按 score 减一、按同毫秒区间反复重扫或使用增长的 OFFSET。
- 按时间清理使用同编码的字典序范围。若另设容量上限，权威兜底需要覆盖保留窗口内因裁剪缺失的区间。

## 异常与恢复

写入前拒绝非法时间、ID 或编码。读到非法 member、错误键类型或检测到非零 score 时，将该派生索引标为不可信，告警并切换权威读取或返回可重试错误。禁止把故障当作耗尽。旧格式与新格式分键，以 MySQL 重建；权威恢复见实施任务清单。编码和查询用真实 Redis 7 验收。

## 验收

T01 同毫秒 9、10、11，limit=1，顺序 11、10、9。T02 同毫秒 501 条，遍历与 SQL 对照完全相同，边界逐次变小。额外覆盖时间跨毫秒、最大 ID、非法输入及 Redis 重放。
