# M2 剩余 + M3 批量实施记录

状态：开发中；最终只提交一个批量 PR，不自动合并。基线 main 8435d41，PR #4 已合并，main run 42419149640 成功（44 unit +75 IT）。V2.1 PDF 只读保留，四份 ADR 优先；M0–M9 路线不变。

## 内部依赖与实施参数

1. 主库读取与关系生命周期：共享 gate→作者模式行→读者图版本→关系行锁；状态 FOLLOWING/UNFOLLOWED，首次关系 epoch=1，图版本从0初始化；重复操作不增代次。默认回填20，最大100；历史及Feed保留窗口默认30天可配置，快照固定窗口参数。窗口/N是部署配置，不在读时扩大已物化历史。作者须已存在（不自动创建账号）。
2. 内部可信 Principal 沿用M1；生产 HTTP 配置额外的运维签发 HMAC bearer 适配，身份与游标使用独立>=32字节密钥、独立用途、有效期校验，不接受用户ID头。实现令牌适配，不开发登录业务。GET /api/feed/following 返回 items,nextCursor,pageState,scanned；游标绑定用户、版本、limit、snapshot、窗口、G/C、图版本、恢复generation、过期时间，默认15分钟。
3. 主库有界候选及权威谓词：每源<=32，整页<=256消费/检查、<=64来源读，线程/连接队列有界；每次权威查询按主键联查当前关系与白名单。生产类完成判定，测试仅准备数据。Redis + 持久化PULL SQL + PUSH主库兜底组成混合来源，后两路按帖子不可变 delivery_mode 分区，不按作者最新模式改判。始终保留PUSH主库兜底，暂不声称减少主库读取的性能收益。
4. Inbox+Task：MQ接收与任务同事务，提交后ACK。PostPublished按持久化payload及帖子核对；PUSH创建任务、PULL持久化NO_FANOUT决定。FollowChanged用于固定历史集合回填，取消不盲删member；FeedModeChanged受理后不改变旧帖子归属。保持原eventId/payload，冲突拒绝并告警。
5. Worker：数据库时间，租约30秒，仅成功批次提交进度时在有效租约内带token/generation续租，每次成功领取计尝试，最多5次，退避 min(60s,1s*2^(attempt-1))；批次最多100人/100历史ID，首次领取固定粉丝ID上界；每次claim递增token，成功/进度/失败校验RUNNING、token及lease_until>NOW，过期边界<=NOW；最后一次过期可DEAD。Redis写在短SQL事务之外，整批成功才推进；DEAD可受控本地命令重放并审计，不更改事件内容。
6. 双向模式切换：与发布/关注共用作者锁，递增epoch、记录迁移及ModeChanged Outbox；旧代次始终由相同权威读取谓词覆盖，DONE必须记录主库覆盖策略/窗口并通过实际故障矩阵，不以仅增加epoch宣称完成。
7. Redis恢复：采用ADR允许的受控维护窗口，不实现未经冻结的无停机追平。持久化维护/generation状态，写入gate等待已有SQL写事务退出，再隔离Redis旧Worker、暂停新发布/关系/迁移/分发写入。基于权威帖子+当前代次谓词重建（包含DONE任务所覆盖帖子），验证覆盖后才切READY；故障/重启保持主库读取。Redis标记丢失、错generation或未就绪不启用缓存；旧会话覆盖无法证明时CURSOR_STALE。独立单节点Redis为本地部署基线，不宣称Cluster跨槽脚本兼容。

## 测试/提交纪律

分内部行为组 RED→GREEN→必要REFACTOR，全部在codex/m2-m3-production，禁止将编译或连接故障算RED。真实MySQL/Redis/RocketMQ，关键故障用屏障/进程终止，保留证据。最终逐项记录T01–T09/T13–T18，T10分阶段沿ADR004，M4–M9不提前实现。未完成项不标通过；本文件持续补充实际提交、日志和风险。

## 实际进展

- 第一组 RED `8456593`：`-Dtest=ContentApplicationTest -Pintegration -Dit.test=ProductionFeedIT verify`，源码编译、真实MySQL/Redis预检通过，HTTP关注路由缺失404导致1 assertion failure、0 errors。
- 第一组 GREEN：主库候选/权威判定、关注/取关及固定历史集、基础HTTP与内部签名游标。`mvn -B -ntp -Pintegration -Dit.test=ProductionFeedIT,MySqlMigrationIT verify` BUILD SUCCESS；44 unit +4 IT（迁移3、生产HTTP链1），0失败/错误/跳过。V4仅新增控制行及排序索引，V1–V3不变。日志target/batch-read-verified.log；尚非本批完整验收。
- 第二组 RED `eedf8a6`：真实MySQL/Redis正常，3个令牌单元通过，合法签名Bearer未接入Principal导致HTTP401（预期200），1 failure/0 errors；日志target/batch-http-red.log。
- 第二组 GREEN：身份HMAC适配、独立用途/密钥、游标绑定与可配置扫描预算；第一轮47 unit+7 IT通过；修正过滤器只捕获认证阶段异常并补游标过期断言后，`-Dtest=FeedTokensTest -Pintegration -Dit.test=ProductionFeedIT verify` 3 unit+7 IT再次通过。PowerShell签发命令的JSON/签名通过独立Python HMAC核对。目标日志target/batch-http-verified.log。未进行登录业务开发，运维密钥持有者为临时身份信任根。
- Inbox组 RED `d1b4230`：真实Broker SEND_OK、Outbox SENT后Inbox为0，1 failure/0 errors；不是连接故障。
- Inbox组 GREEN：V5扩展Inbox快照摘要、任务不可变内容及冲突/重放审计；Relay保留原PostPublished协议并增加FollowChanged/ModeChanged/Offlined标签路由；消费者提交后ACK。`-Dtest=ContentApplicationTest,FeedTokensTest -Pintegration -Dit.test=DistributionIT verify` BUILD SUCCESS，4 unit+6 IT，0失败/错误/跳过；实际覆盖MQ受理、PULL无需扇出、并发重放、真实SQL触发器回滚、提交后失败及冲突对账。V5迁移的3个迁移集成测试也已通过。日志target/batch-inbox-current.log。
- 调试失败不计通过：修复MyBatis方法重名；MQ预检改为单探针有界读取；新消费者启动元数据RPC10秒、最多3次，失败清理资源并拒绝启动（不修改Relay发送3秒/租约30秒协议）。扫描业务测试尚待后续组完成。

- M3任务续租为本组实施选择：同一有效领取内允许完成任意有限上界的多批次，续租与进度同一CAS；过期或旧token不能续租。只在重新领取时计attempt，5次上限及最后一次崩溃DEAD仍生效。M1 Relay无续租协议保持原样。
- Worker组 RED `7ec36ad`：真实MySQL/Redis和Inbox成功后任务NEW，预期DONE，1 assertion failure/0 errors。
- Worker组 GREEN：领取/固定粉丝上界、100人批次、SQL状态/token/有效租约/generation CAS、成功批次续租、退避/DEAD及审计重放。`mvn -B -ntp -Pintegration -Dit.test=DeliveryWorkerIT verify` BUILD SUCCESS，47 unit+10 IT，0失败/错误/跳过。实际含101接收者两批一次领取、并发领取、过期两种旧token、最后一次崩溃DEAD、真实Redis暂停/部分成功、Redis后SQL失败、旧代次回填与旧Worker迟到I/O/重关注可见性。日志target/batch-worker-current.log。这里的进程故障尚以屏障与SQL故障注入覆盖；独立JVM kill尚待本批后续验收，不能提前算通过。
- 默认关闭启动回归修复了测试Hook隔离；扫描器仅经启用的外层配置导入。全局锁顺序为gate→task，状态提交不使用可能反向锁control的UPDATE JOIN。
