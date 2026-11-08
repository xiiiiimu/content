# M2 剩余 + M3 批量实施记录

状态：开发中；最终只提交一个批量 PR，不自动合并。基线 main 8435d41，PR #4 已合并，main run 42419149640 成功（44 unit +75 IT）。V2.1 PDF 只读保留，四份 ADR 优先；M0–M9 路线不变。

## 内部依赖与实施参数

1. 主库读取与关系生命周期：共享 gate→作者模式行→读者图版本→关系行锁；状态 FOLLOWING/UNFOLLOWED，首次关系 epoch=1，图版本从0初始化；重复操作不增代次。默认回填20，最大100；历史及Feed保留窗口默认30天可配置，快照固定窗口参数。窗口/N是部署配置，不在读时扩大已物化历史。作者须已存在（不自动创建账号）。
2. 内部可信 Principal 沿用M1；生产 HTTP 配置额外的运维签发 HMAC bearer 适配，身份与游标使用独立>=32字节密钥、独立用途、有效期校验，不接受用户ID头。实现令牌适配，不开发登录业务。GET /api/feed/following 返回 items,nextCursor,pageState,scanned；游标绑定用户、版本、limit、snapshot、窗口、G/C、图版本、恢复generation、过期时间，默认15分钟。
3. 主库有界候选及权威谓词：每源<=32，整页<=256消费/检查、<=64来源读，线程/连接队列有界；每次权威查询按主键联查当前关系与白名单。生产类完成判定，测试仅准备数据。Redis + 持久化PULL SQL + PUSH主库兜底组成混合来源，后两路按帖子不可变 delivery_mode 分区，不按作者最新模式改判。始终保留PUSH主库兜底，暂不声称减少主库读取的性能收益。
4. Inbox+Task：MQ接收与任务同事务，提交后ACK。PostPublished按持久化payload及帖子核对；PUSH创建任务、PULL持久化NO_FANOUT决定。FollowChanged用于固定历史集合回填，取消不盲删member；FeedModeChanged受理后不改变旧帖子归属。保持原eventId/payload，冲突拒绝并告警。
5. Worker：数据库时间，租约30秒，仅成功批次提交进度时在有效租约内带token/generation续租，每次成功领取计尝试，最多5次，退避 min(60s,1s*2^(attempt-1))；批次最多100人/100历史ID，首次领取固定粉丝ID上界；每次claim递增token，成功/进度/失败校验RUNNING、token及lease_until>NOW，过期边界<=NOW；最后一次过期可DEAD。Redis写在短SQL事务之外，整批成功才推进；DEAD可受控本地命令重放并审计，不更改事件内容。
6. 双向模式切换：与发布/关注共用作者锁，递增epoch、记录迁移及ModeChanged Outbox；旧代次始终由相同权威读取谓词覆盖，DONE必须记录主库覆盖策略/窗口并通过实际故障矩阵，不以仅增加epoch宣称完成。
   模式PUT表达目标状态：已处于目标模式时返回当前epoch，不生成迁移或事件；只有实际切换才要求expectedEpoch等于当前epoch，否则409。覆盖验证另持久化VERIFYING/DONE及策略；验证失败不伪报DONE。此处不改变发布接口的expectedVersion及幂等冲突检查顺序。
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

## 最后一组协议与覆盖定位（完整回归中）

- RED `071e992`：模式 HTTP 缺失404，以及已完成任务对应的 Redis 数据被清空后没有权威重建，2 failures/0 errors；数据库、Redis及编译正常。
- V6新增恢复所有权/审计及迁移覆盖记录，不更改V1–V5。模式迁移的覆盖策略是 `PRIMARY_ALL_MODES`：始终按每帖不可变模式划分两个完整主库来源，保留旧epoch，不用缓存水位代替覆盖证明。双向切换、重复请求、逆序/重复事件及下架使用同一权威谓词。
- 生产候选SQL联查当前 FOLLOWING 关系，两个模式分区；并非测试夹具，也不按作者数创建无界来源。默认每页至多64候选SQL回调、256权威检查，加6次上下文SQL；READY时额外至多2次Redis就绪元数据读取。预取返回的数据量受每源批次限制。数据库物理扫描行数仍取决于索引与数据分布；记录 EXPLAIN ANALYZE，不据此虚构容量承诺。
- 初次建连/连接池等待上限5秒，SQL语句/归并回调/已连接Redis命令上限2秒。冷启动初始化与单次业务回调分开：连接池初始化失败窗10秒，Redis保持惰性建连；Redis故障不阻止主库API启动。早先全回归中出现建连2秒过紧的问题，该失败轮不计通过。任务30秒租约、有效租约内续租及回滚规则不变。
- 恢复租约30秒，按数据库时间在每个至多100人/100条边界续租；过期不可续租。generation及recovery token同时隔离旧操作。先持久化REBUILDING，再Redis重建与逐页逐项核对，最后Redis证明和SQL条件READY；任一中途失败保持主库读。控制状态锁不跨Redis I/O持有。恢复不支持无停机追平。
- 单节点Redis显式要求 `noeviction`。裁剪每轮最多100读者、每读者最多100过期member，检查generation及member合法性；不盲删旧关注代次。人为篡改单键仍需受控重建，完整主库来源不依赖缓存完整性。
- 身份签发/重放/恢复均以OS与部署凭据为边界，无公开管理HTTP接口。`pipeline`显式开启生产链路，两个独立密钥无内置默认值；API与Worker可分进程，运维命令强制停用全部后台发送/消费/扫描。
- Windows全回归中，SDK `ClientConfig` 及 `NettyConnectManageHandler.connect` 的 `NetworkUtil.getLocalAddress→NetworkInterface.isP2P0` 原生调用显著拖慢首次连接；独立线程记录见本地target/batch-thread*.txt、consumer-child-thread.txt。消费者进程实际启动约78–87秒，父上下文一次约193秒（含元数据重试）。原测试正常链路30秒预热未完成导致1 error，不是业务RED；改为每例先取得真实 SEND_OK 的180秒有界环境预热并记录耗时。独立进程屏障仍限180秒，并要求进程存活、目标屏障、强制终止非零退出及恢复断言；生产Relay的3秒发送/30秒租约不变。
- 同轮既有Relay回归出现2 failures：ACK故障子进程启动约94秒后30秒预热超时并退出（父进程确实拒绝把它计为屏障成功）；停Broker时客户端实际约15秒失败，超过该旧测试人为缩短的10秒租约，正确保持RUNNING而非接受过期失败更新。修正仅在测试：父上下文采用生产同值30秒租约；故障子进程仍10秒。ACK/恢复进程预热上限120秒，整个子进程启动/预热看门狗300秒；必须有真实 SEND_OK、存活/退出码与目标屏障，预热耗时另存`.warmup`。停Broker恢复前用同一生产客户端取得真实ACK确认TCP恢复，再消费剩余业务尝试。生产配置、租约检查与发送参数未改变。此处替代M2 Story 1历史记录中的180秒Relay测试启动看门狗，不修改其当时结果。
- 后续完整回归中，Inbox子进程在约71/133秒分别记录两次元数据超时，原180秒测试启动看门狗先于第三次尝试完成。单纯延长业务租约不能解决这一启动问题。当前消费者元数据RPC改为可配置 `content.pipeline.metadata-timeout-millis`，默认60000，范围1000–120000毫秒；仍最多3次启动，失败清理且拒绝启动。Pipeline故障进程启动看门狗也改为300秒，必须取得真实事务屏障，若进程退出立即失败。此为本地证据驱动的消费者连接参数调整，替代前文历史实现的10秒初值；Relay发送3秒/30秒租约、Task30秒及ACK/事务规则均不变。正在执行受影响MQ测试复验，未提前标为通过。

| 矩阵 | 实际测试定位与证据范围 |
| --- | --- |
| T01/T02 | RedisFeedCandidateStoreIT、FeedMergerTest/IT及ProductionFeedIT同毫秒501条HTTP游标遍历，逐项核对SQL，trace记录边界。 |
| T03/T15 | ProductionFeedIT连续空UNKNOWN与最终耗尽、HMAC篡改/换人/limit/过期/图版本；RecoveryAndModesIT在响应前改变图版本。 |
| T04/T17 | RecoveryAndModesIT对实际发布/模式/关注服务安排两种作者行锁顺序，SQL锁等待确认竞争，不以sleep猜顺序。 |
| T05/T18 | RecoveryAndModesIT双向反复切换、旧epoch不变、乱序重复事件/下架、Redis丢失后的主库读取及权威重建；审计记录实际恢复耗时。 |
| T06 | DistributionIT独立消费者JVM分别在Inbox提交前、提交后ACK前屏障被强制终止，父进程同组重新接收原Broker消息；无显式重发替代该证据。 |
| T07 | DeliveryWorkerIT真实Redis部分成功/暂停、实际SQL触发器失败，以及独立JVM在Redis成功后进度提交前终止。 |
| T08 | DeliveryWorkerIT有效租约唯一领取、过期未接管/已接管旧token、旧Worker迟到写；恢复组验证跨generation及旧恢复token均被隔离。 |
| T09 | ProductionFeedIT固定N、不递补、重关注；DeliveryWorkerIT旧代次回填/迟到写；生产历史时间窗精确下界及前1毫秒用MySQL事务内固定session时钟验证并复位。 |
| T13/T14 | 既有FeedMergerTest/IT全来源头确认、跨源重叠/预取安全重读、L1迟到/来源超时；生产HTTP来源通过同一归并器及主库权限检查。 |
| T16 | RecoveryAndModesIT真实Redis KILL/start，独立恢复JVM批次后被终止，无READY证明时保持主库读，实际运维命令接管恢复。 |

以上为测试定位，最终通过数量与完整回归/CI结果在验收完成后补录。屏障故障使用测试专用observer，不进入生产JAR；数据库时间到期通过隔离测试库设置为当前时间之前，不能算真实等待30秒的性能结果。M4–M9、无停机恢复、Redis Cluster、容量基准和完整媒体可见性均不在本批。
