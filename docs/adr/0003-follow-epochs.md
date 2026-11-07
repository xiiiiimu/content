# ADR-003 关注代次与历史回填

状态：已采纳，待实现与验收。基线：SDD V2.1 §6–7。

## 决策与数据

每次从未关注变为关注，生成新的 follow_epoch。重复关注请求不创建新代次。保留 follows 当前代次和状态，维护读者级 follow_graph_version，在关注或取关的同一事务递增。游标绑定该版本。

发布沿用作者模式行锁。在持有该锁时为作者递增持久化 publish_seq，并在真正进入 PUBLISHED 的事务中写入帖子。模式切换仍按 mode_epoch 归属。publish_seq 仅供关注边界使用。

关注事务按固定锁顺序取得同一作者行锁，然后锁定读者关注图版本和关系行。记录该作者当前 publish_seq 作为 follow_cutoff_seq，并固定 UTC 回填时间下界及 N。在同事务内按 (published_at_ms,id) 倒序选取 cutoff 以内、窗口内、当前已发布的前 N 个 ID，存入 `follow_history(follower_id,followee_id,follow_epoch,post_id)`，以四列为唯一键。空集合也是确定结果。事务写 FollowChanged Outbox；异步任务按该集合回填。下架后不递补历史名额。取关和重关注都采用同一锁顺序。回填集合须有配置上限并用执行计划验证持锁成本。

## 写入与读取

- 正常分发只向扫描时已关注且 `post.publish_seq > follow_cutoff_seq` 的关系写入。回填 Worker 校验任务代次仍为活跃代次，并只写已物化 ID。两者使用 ADR-001 的同一 member。
- Redis 与 MySQL 跨系统间存在检查后状态变化的窗口。所有 Feed 来源批量读取权威关系和可见性，只有当前仍关注且 `publish_seq > 当前代次 cutoff` 或帖子 ID 属于当前代次回填集合才允许返回。
- 旧 Worker 可以迟到写同一 member，但无法改变帖子发布时间、当前代次 cutoff 或白名单，因此无法扩张历史范围。异步取关清理不得按旧代次盲删共享 member。首版依靠读取过滤和时间裁剪；未来清理需有独立安全协议。
- Redis 重建复用同一读取谓词。对账从当前关系、回填集合和帖子权威索引生成候选。

## 验收

T09 在旧 Worker 读完关注关系后暂停，再取关、重关注，最后释放旧任务。验证取关后不可见、重关注后旧代次越界帖不可见、回填集合内帖子可见且只出现一次、新关注后的发布可见。另测两种发布和关注加锁顺序、重复关注、旧清理与新回填重叠。
