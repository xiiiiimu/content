# 平台加固计划（P0–P3）

> 建立：2026-11-21 · 基线提交 `eddba0c`（分支 `feat/v1.1-material-commerce`）
> 范围：P0–P3。**不做部署相关工作**（不容器化、不上 Linux 服务器、不改中间件拓扑）。
> 前置文档：`docs/platform-concurrency-risks.md`（风险 R1–R10）、`docs/perf-evidence/interactions/interaction-fix-round1-20261121.md`（第 1 轮）。

## 使用规则
- **每轮开始前先读本文件，每轮结束时更新各项状态**，并在“变更记录”里追加一行。
- 改业务行为先写 RED 再写 GREEN。合格的 RED：编译通过、环境健康、因缺少目标行为而失败。按主题分别提交；每轮结束跑完整集成测试：用 `infra/test/run_it_batches.py` 分批运行（每批 1–2 个测试类，failsafe JVM `-Xmx1g`，每批前可用内存 ≥3GB，每批后确认 Testcontainers 容器退出），全部批次通过才算通过。
- 涉及性能的项都做改前改后对比，报告写入 `docs/perf-evidence/` 下的新目录；环境条件和采集方式与之前保持一致，不靠放宽超时或调大连接池过关。
- 标 **【需决定】** 的项：先给出设计或对比，**停下来等确认再实现**。
- 每轮结束给一份简短报告（改了什么、测试结果、数据对比、遗留问题）。**推送前等确认**。
- 私有 token、原始数据只放 `target/`；提交前扫描密钥和大文件。

**状态**：`未开始` / `进行中` / `待确认`（设计已给出，等确认）/ `完成` / `阻塞` / `不做`

## 总览

| 项 | 标题 | 优先级 | 状态 |
|---|---|---|---|
| 0 | 上一轮收尾（提交、扫描、推送、CI） | — | 完成 |
| A1 | 调试接口仅在显式测试配置下注册 | P0 | 完成 |
| A2 | 互动/登录/注册限流（已决定） | P0 | 完成 |
| A3 | 举报去重（已决定） | P0 | 完成 |
| A4 | 登录 BCrypt 有界线程池 | P0 | 完成（池大小默认值已决定并修改） |
| A5 | 评论幂等竞态 | P0 | 完成 |
| A6 | 注册并发重名返回 409 | P0 | 完成 |
| A7 | 媒体下载签名密钥去掉公开默认值 | P0 | 完成 |
| A8 | `GET /api/assistant/tasks/{id}` 需登录且仅发起人可看 | P0 | 完成 |
| A9 | 分享密码解锁限流 | P0 | 完成 |
| A10 | 相同幂等键下评论话题统计丢失（已决定） | P1 | 完成 |
| A11 | 助手指令的 Java 端校验缺口 | P0 | 完成 |
| A12 | 反馈 resolve 缺 `versionId` 返回 400 | P2 | 完成 |
| A13 | 登录时用户不存在也执行 BCrypt | P1 | 完成 |
| B1 | 同一话题分钟计数热点【需决定】 | P1 | 未开始 |
| B2 | 计数改为增量更新 + 离线对账（R3） | P1 | 未开始 |
| B3 | 每次点赞 3 次连接获取 | P1 | 未开始 |
| B4 | 发送端空轮询退避 | P1 | 未开始 |
| B5 | 抢购缓存同步提交次数【需决定】 | P1 | 未开始 |
| B6 | 关注潮（R6）与推模式扩散（R7）实测 | P1 | 未开始 |
| C1 | 只增不减的表和 Redis 结构盘点 | P2 | 未开始 |
| C2 | 保留策略与清理任务【需决定】 | P2 | 未开始 |
| D1 | CI 跳过项评估与启用【需决定】 | P3 | 未开始 |
| D2 | FFmpeg 超时后的容器清理测试 | P3 | 未开始 |
| D3 | `offlineDuringProcessingFailsLeaseWithoutCompleting` 偶发失败 | P3 | 未开始 |
| D4 | 前端话题输入框 + 前端测试 | P3 | 未开始 |
| D5 | 支付/退款为模拟实现的文档说明 | P3 | 未开始 |

---

## 0. 上一轮收尾
- **内容**：提交 `docs/platform-concurrency-risks.md`、第 1 轮报告和小体积汇总（`docs/perf-evidence/interactions/round1-20261121/`）、`infra/perf/interactions/` 工具；确认 `target/` 下的密钥和原始数据没有被包含；推送并等 CI。
- **已完成**：
  - `0b9d5b7` chore(perf) 工具、`eddba0c` docs(perf) 风险清单与报告
  - 扫描推送范围 `8c88b75..eddba0c`：没有 `target/` 路径，没有 `.jar`/`.env`，没有 >100 KB 的文件；测试签名密钥、MySQL root/应用口令在 diff 中都是 0 次命中；`*.tsv` 被 `.gitignore` 排除，没有提交
  - 已推送 `eddba0c`
- **CI**：run `44773707871` 通过（success，11 分 43 秒，2026-11-20T16:28Z–16:39Z）。附注：`actions/setup-java@v4` 已提示弃用，可在 D1 一并升级到 v5。
- **验收**：CI 通过；提交中没有私有数据。

---

## 第 A 轮：P0 安全与正确性

### A1 调试接口仅在显式测试配置下注册
- **追加要求**：A1 修复后，全仓库检查所有接口的认证要求，在报告中单独列出不需要登录、但会改变状态或暴露内部信息的接口。
- **问题**：测试钩子在正常运行配置下可能可以访问，并且不需要身份。
- **证据（静态审查，待运行时核实）**：
  - `SaleGiveawayDebugController`（`/api/sale-debug/*` 赠品）是**类级别**的 `@ConditionalOnProperty(content.sale.fault-hooks=true)`，默认不注册。✔
  - `SaleController` 里的 `/api/sale-debug/hold-settle`、`/fail-after-commit-once`、`/inflight`、`/reset-inflight-peaks`（`SaleController.java:68-100`），以及 `/api/purchase-requests/{id}/settle`（`:60`，`content.sale.manual-settle`）把 `@ConditionalOnProperty` 写在了**请求映射方法上**。Spring 的条件注解只对配置类和 `@Bean` 方法生效，**放在 handler 方法上不起作用**。因此只要 `content.media.enabled=true`（抢购启用），这 5 个端点就会注册。
  - `SaleFaultHooks` 本身没有开关（`SaleConfiguration.java:19` 无条件创建 Bean），`hold-settle` 会挂起结算；`hold-settle`、`fail-after-commit-once` 都不检查用户。**若核实属实，这是 P0：未登录的请求就能让抢购结算挂起。**
  - 说明：`application.yml` 默认 `content.media.enabled=false`，此时整个 `SaleController` 都不注册。所以本项的“默认配置”指**正常启用抢购、但没有显式打开 fault-hooks 或 manual-settle** 的配置。
- **待核实**：启动应用（media 启用，fault-hooks 不设置），请求上述 5 个端点，记录状态码。另外全仓搜索其他写在方法上的条件注解，以及其他 debug、test 前缀的路由。
- **验收**：
  1. 新 IT 在 media 启用、fault-hooks 和 manual-settle 都不设置时，对上述每个端点断言返回 404（RED：当前预期返回 200）。
  2. 显式打开时，端点仍然可用，现有压测和正确性脚本（`run-java-commerce.ps1` 传 `--content.sale.fault-hooks=true`）不受影响。
  3. `SaleFaultHooks` 在关闭时不能被外部触发。
- **结果**（`0480365`）：钩子移到类级条件的 `SaleFaultHookController`、`SaleManualSettleController`。RED：默认配置下 4 个钩子返回 200、`/settle` 返回 401（已注册），`holdArmed` 为 true；GREEN：`DebugEndpointsDefaultIT`（全部钩子与赠品钩子 404）、`DebugEndpointsEnabledIT`（显式打开时可用）通过。
- **认证盘点结果**（`caff87e`，`AnonymousAccessIT` 匿名调用全部路由，表格写入 `target/anonymous-access/routes.tsv`）：不需要登录即可访问的只有注册、登录（A2 限流）和带 HMAC 签名的 `GET /api/media/objects`；发现 `GET /api/assistant/tasks/{id}` 匿名可读（→ A8，已修复，KNOWN_FINDINGS 已清空）；签名密钥有公开默认值（→ A7）；分享密码解锁无限流（→ A9）。Actuator 只暴露 health 和索引，env/metrics/beans/configprops/heapdump 均 404。另发现两条小问题，已立项并修复：素材反馈 resolve 缺 `versionId` 时 NPE → 500（→ A12）；不存在的用户名登录跳过 BCrypt，响应时间可区分用户是否存在（→ A13）。

### A2 限流（已决定 2026-11-21）
- **问题**：点赞/取消/踩、评论、举报、登录、注册都没有限流（R4/R5）；全仓只有 `SaleDispatcher` 有并发闸门。
- **证据**：`docs/platform-concurrency-risks.md` R4/R5；第 1 轮压测中，同一话题饱和时 Hikari pending 打满到 183。
- **决定**：
  - 算法：Redis 令牌桶，Lua 原子执行。
  - 默认值（全部可配置）：

    | 操作 | 维度 | 速率 | 突发 |
    |---|---|---|---|
    | 点赞/取消/踩 | 用户 | 10/s | 20 |
    | 评论 | 用户 | 1/s | 5 |
    | 举报 | 用户 | 5/分钟 | 5 |
    | 登录 | IP | 20/分钟 | 20 |
    | 登录 | 用户名 | 5/分钟 | 5 |
    | 注册 | IP | 10/小时 | 10 |

  - 超限：返回 429，带 `Retry-After`（秒，向上取整）和固定的错误码。
  - **Redis 故障时**：退回到每个实例的本地内存令牌桶（单机粒度），记录 WARN 日志和指标；不全部放行，也不全部拒绝。
  - 与抢购隔离：不覆盖抢购入口，也不改 `SaleHttpInFlightFilter`。
  - **压测冲突**：修改 `infra/perf/interactions` 的压测脚本，把请求分散到足够多的用户，使每个用户的速率低于阈值；**不为压测单独调高阈值**。报告中说明这一改动，并确认不影响第 1 轮的结论。
- **验收**：每类接口的 IT 都能证明阈值内放行、超限返回 429 加 Retry-After、窗口过后恢复、Redis 不可用时的行为符合设计；抢购入口的 IT 不受影响；互动 a/b/c 场景在约定配置下的结果与第 1 轮一致（误差范围在报告中写明）。
- **结果**（`ffc3fa0`、脚本 `4f5cc94`）：`RateLimitIT`（7 项）、`RateLimitRedisDownIT`（Redis 端口不通时按本地桶放行 5 个、拒绝 2 个，记 fallback 指标和 WARN）通过。压测复核见 `docs/perf-evidence/hardening-a-20261121/README.md` §2：新脚本下所有档 4xx = 0，第 1 轮结论不变（(a) 200 rps 无错误；(b)(c) 仍卡在约 55–64 rps，属 B1）；限流单次判定耗时缺证据。默认配置下单 IP 登录每 30 s 成功 29 次，符合 20/分钟 + 突发 20。

### A3 举报去重（已决定 2026-11-21）
- **问题**：`post_reports` 没有唯一约束（`V14__material_posts.sql`），同一用户可以无限次举报，每次还要做 4 次 COUNT（R4）。
- **决定**：唯一约束 `(post_id, reporter_user_id)`，同一用户对同一帖子只能举报一次；重复请求返回首次的 `reportId` 和计数。迁移时现有重复数据保留最早一条（迁移前先统计数量）。`reportId` 返回真实 id。
- **待核实**：开发库里现有重复举报的数量（迁移前统计）；`reportId` 目前固定返回 0（`InteractionService.report`），需要返回真实 id。
- **验收**：RED 证明重复举报会新增记录；GREEN 后重复请求不新增，返回同一个 `reportId` 和计数；两个线程同时举报只产生 1 条记录，且都不返回 5xx；迁移在已有重复数据的库上也能成功执行（有测试）。
- **结果**（`9a5b366`，V29）：
  - 迁移前统计：开发库 `content`（当时在 V26）重复举报 0 行、0 组（`target/hardening-a/A3-premigration-dup-count-010700.txt`）。
  - RED：8 线程并发举报产生 8 行；重复举报产生 2 行。GREEN：`ReportDedupIT`（3 项：重复返回同一 `reportId` 和计数且 `replay=true`、并发只 1 行且无 5xx、`reportId` 为真实 id）通过。
  - `ReportDedupMigrationIT`：在 V28 库中插入重复数据后迁移到 V29，每组保留最早一条（同时间按 id），`report_count` 按实际行数重算，唯一索引存在。`MySqlMigrationIT` 增加 V29 断言。

### A4 登录 BCrypt 有界线程池
- **问题**：BCrypt 在请求线程上同步计算，每次约 61.7 ms CPU（风险清单 R5），没有上限。
- **方案方向**：固定大小的线程池加有界队列，满了直接返回 429（带 Retry-After），与 A2 的登录限流叠加。池大小和队列长度可配置，默认值在设计里说明。注册时的 BCrypt 编码同样处理。
- **验收**：IT 或单元测试证明并发超过池加队列容量时返回 429 而不是排队到超时；正常登录不变；并发压测（与风险清单 D 节同方法）记录改前改后的 CPU、p99、429 比例。
- **结果**（`b44959c`）：`PasswordHasher`（默认线程 = 核数/2，队列 = 4×线程，等待上限 5 s 后 503 `AUTH_BUSY`），注册的编码移到事务外；`AuthBcryptPoolIT`（1 线程、队列 1、16 并发：部分 429 + Retry-After: 1，其余 200）通过。登录压测见 `docs/perf-evidence/hardening-a-20261121/README.md` §3：隔离运行（仅调高 login-ip）在 200 rps 下改前丢弃 2079 次迭代、p99 5.0 s、CPU 峰值 88.5%，改后无丢弃无 5xx、p99 1.5 s、CPU 峰值 55.8%，成功约 64/s，其余 429。
- **遗留【需决定】**：16 逻辑核下默认 8 线程，成功吞吐上限约 64–75/s；改前 100 rps 时全部成功，改后 100 rps 有 24% 被 429。是否调整 `content.auth.bcrypt-threads` 默认值（例如按核数减 1），或保持保守默认、部署时按机器配置，请决定。
- **决定（2026-11-21）**：默认值改为 `max(2, 逻辑核数/2)`，仍可通过 `content.auth.bcrypt-threads` 配置。理由：登录突发时为其他接口保留一半 CPU；更高的登录容量靠横向扩展实例，而不是加大单机线程池；下限 2 保证小机器也能同时处理两个登录。
- **结果**（`0d171dc`）：`PasswordHasherDefaultsTest`（1/2/3/4 核 → 2，8 核 → 4，16 核 → 8）RED 为 1 核得 1，GREEN 通过；理由写入 `PasswordHasher.defaultThreads` 与 `FeedConfiguration.passwordHasher` 注释。16 核机器上默认值不变（8），上面压测结论仍适用。

### A5 评论幂等竞态
- **问题**：`InteractionService.comment` 先 `existingComment` 再 `INSERT`（`:73,92`）。同一个幂等键并发到达时，后到的请求撞唯一键 `uq_comment_idem`，`DuplicateKeyException` 没有被捕获，返回 500（R10）。
- **方案**：参照 `PublishService.publish`：捕获唯一键冲突，**在新事务里**重读首条记录，返回 `replay=true` 的首次结果；不在已经失败的事务里读取。
- **验收**：RED 用屏障让两个线程同时提交同一个键，其中一个当前返回 5xx；GREEN 后两者都返回同一个 `commentId`，并且只有 1 条评论、1 个 outbox 事件、1 次话题计数。
- **结果**（`982e742`）：RED 实际为 503 `FEED_UNAVAILABLE`，不是计划中写的 500（`FeedErrors` 把 `DataAccessException` 映射为 503）；GREEN `CommentIdempotencyRaceIT`（8 线程同键：1 条评论、1 个 CommentCreated outbox 事件、话题净值 1、`comment_count` 1）通过。

### A6 注册并发重名返回 409
- **问题**：`AuthService.register` 在事务里先查后插，并发重名撞 `uq_users_username` 时返回 500。
- **验收**：RED 用屏障并发注册同一用户名，当前有 1 个返回 5xx；GREEN 后一个 200、一个 409 `USERNAME_TAKEN`，不出现 500，只有 1 条用户记录。
- **结果**（`f0881d4`）：RED 实际为 503 `FEED_UNAVAILABLE`；GREEN `AuthRegisterRaceIT`（同名 8 并发、大小写变体并发：恰好一个 200，其余 409 `USERNAME_TAKEN`，1 条用户记录）通过。

### A7 媒体下载签名密钥去掉公开默认值
- **问题**：`/api/media/objects` 只靠 HMAC 签名授权；`application.yml` 给 `content.media.secret-key` 配了公开默认值 `content-secret-key-32-bytes-minimum`。生产若没有设置 `CONTENT_S3_SECRET_KEY`，任何人都能伪造任意对象（包括付费原件）的下载链接。
- **证据**：A1 认证扫描；`HttpS3ObjectStore.verifyGet`/`mac`。
- **决定**：去掉公开默认值；非开发配置下未设置密钥时启动直接失败；开发配置使用随机生成的密钥并打印警告。
- **验收**：RED/GREEN：未设置密钥的非开发配置启动失败；设置为旧的公开默认值也视为未设置；开发配置下自动生成随机密钥（两次启动不同）并打印 WARN；已有媒体 IT 通过。
- **结果**（`89f9816`）：`MediaSecret.resolve`；`MediaSecretStartupIT` 4 项通过；9 个媒体相关 IT 改用测试专用密钥。注意：该密钥同时是 S3 SigV4 的 secret（MinIO 密码），部署时两处须一致。未纳入本轮的 `AssistantEvalE2EIT`（他人未提交文件）仍使用旧默认值，启动会失败，需要其作者改为显式测试密钥。

### A8 `GET /api/assistant/tasks/{id}` 需要登录且仅发起人可看
- **问题**：该接口不检查身份，任何人可按 id 遍历读取任务的状态、意图和回复评论 id。
- **证据**：A1 认证扫描（`AnonymousAccessIT.KNOWN_FINDINGS`）。
- **决定**：需要登录；只有任务发起人（`actor_user_id`）或该帖子的作者可以查看，其他人返回 404（不暴露任务是否存在）。
- **验收**：RED/GREEN：匿名 401、其他用户 404、发起人和作者 200；`AnonymousAccessIT` 的 `KNOWN_FINDINGS` 清空；`demo-material-assistant.ps1` 的调用方式仍然可用（带发起人 token）。
- **结果**（`88668db`）：RED 为匿名 200；GREEN `AssistantTaskAccessIT`、`AnonymousAccessIT`（KNOWN_FINDINGS 已清空）、`AssistantFlowIT` 通过。演示脚本用评论者本人 token 调用，符合新规则（未实跑演示脚本）。

### A9 分享密码解锁限流
- **问题**：`POST /api/materials/{post}/unlock` 校验分享密码（BCrypt），没有限流，也没有有界线程池，可被暴力尝试。
- **决定**：复用 A2 的限流组件，按用户和按 IP 限流；失败次数多时退避。
- **验收**：RED/GREEN：超过阈值返回 429 + Retry-After；连续失败后退避时间变长，成功后不再额外惩罚；不同用户/IP 互不影响；BCrypt 走 A4 的有界线程池。
- **取值**：`unlock-user` 每用户 10/分钟（突发 10）；`unlock-ip` 每 IP 30/分钟（突发 30）；退避按 (用户, 帖子)：连续 5 次密码错误后，下一次须在上次失败后等待 1s、2s、4s…，上限 15 分钟；成功即清零，最后一次失败 1 小时后自动过期。均可通过 `content.ratelimit.rules.unlock-*`、`content.ratelimit.backoff.unlock.*` 配置。退避状态存 Redis（Lua，使用 Redis TIME），Redis 不可用时与 A2 相同退回每实例内存。
- **结果**（`056a65a`、`3627911`）：RED 为三项均返回 200、16 路并发全部 200；GREEN `UnlockRateLimitIT` 3 项、`UnlockBcryptPoolIT`（1 线程、队列 0，部分请求 429 RATE_LIMITED + Retry-After: 1，其余 200，无 5xx）、`RateLimitIT`、`RateLimitRedisDownIT`、`MaterialFlowIT` 通过。分享密码的创建、修改和校验现在都走 A4 的 `PasswordHasher`。

### A10 相同幂等键下评论话题统计丢失（已决定 2026-11-21）
- **问题**：评论的话题事件 id 为 `"comment:"+Idempotency-Key`，没有按用户区分。两个用户恰好使用相同的幂等键时，第二个用户的评论不会计入话题统计（ledger `INSERT IGNORE`）。
- **计划**：先分析和复现，给出结论（影响范围、客户端幂等键的实际生成方式），修复与否等决定。
- **复现**（`aa75ca7`，`CommentTopicEventIdIT`，当前 `@Disabled`）：用户 2101 在 sports 帖、用户 2102 在 music 帖，使用相同幂等键 `k-1` 评论。两条评论都创建成功（评论幂等按 (user_id, key)），sports 计 1，music 计 **0**。失败日志：`target/hardening-a/A10-repro-025840.log`。
- **结论**：
  - 原因：`topic_event_ledger.event_id` 全局唯一，评论事件 id 为 `"comment:"+key`，作用域比评论幂等（按用户）大。不同用户的同键评论，第二条的 ledger `INSERT IGNORE` 不生效，分钟桶不 +1，也不会触发 `emitSpikeIfNeeded`；`rebuild` 从 ledger 重建时同样缺失。评论本身、`comment_count`、Outbox 事件都不受影响，只影响话题热度统计和突增检测。冲突仅在 ledger 保留窗口内有效。
  - 实际触发概率不低：前端的键是毫秒时间戳，`posts/[id].vue` 用 `ui-${Date.now()}` 和 `assistant-${Date.now()}`，`hot-topics/[topic]/[post].vue` 用 `web-${postId}-${Date.now()}`。两个用户在同一毫秒发评论就会冲突，前两种键不含帖子 id，跨帖子也会冲突。评论量越大，丢失越多。
  - 可被利用：键可预测，用户可以提前用未来时间戳的键发评论，压制其他人评论的话题计数（影响仅限热度统计）。
  - 附带发现（不属于 A10）：前端每次点击都生成新键，网络重试不会复用同一个键，所以评论幂等对用户重试实际不起作用；抢购页已用 `sessionStorage` 保存键，可参照。
- **修复方案（待决定）**：
  1. **推荐**：事件 id 改为 `"comment:"+commentId`。ledger 写入与评论插入在同一事务，重放路径不记账，每条评论恰好计一次；不需要迁移，旧格式的行在保留窗口后自然过期，新旧格式不冲突。
  2. 事件 id 改为 `"comment:"+userId+":"+key`，与评论幂等作用域一致，效果相同；但 `event_id` 为 `VARCHAR(64)`，用户 id 加长幂等键可能超长（现有 `"comment:"+key` 在键超过 56 字符时同样有此风险，需另核实键长度校验）。
  3. 维持现状，只在文档中说明。
  - 前端幂等键的问题另列，是否纳入本计划也请决定。
- **决定**：采用方案 1，事件 id 改为 `comment:<commentId>`；复现测试去掉 `@Disabled` 作为 RED。前端幂等键问题并入 D4。
- **结果**（`b0d31e0`）：RED `target/hardening-a/A10-red-043019.log`（music 计 0）；GREEN `CommentTopicEventIdIT`（两用户同键各计 1、重放不重复计数、ledger 中为 `comment:<commentId>`）、`InteractionsAndTrendingIT`、`CommentIdempotencyRaceIT` 通过。不做迁移：旧格式行按 ledger 保留策略过期，与新格式不冲突。

### A11 助手指令的 Java 端校验缺口
- **问题**：Java 端对 Python 解析结果的校验不完整（详见助手评测报告，`assistant-python/eval/`）：
  - 不支持的条件被静默丢弃后仍创建活动；
  - 越权执行：素材不可商用却把商用授权作为奖品、奖品为其他帖子、作者本人参与等；
  - 不该执行的订阅被执行。
- **要求**：Java 端对解析结果做完整校验，任何不支持或越权的条件都拒绝或补问，不得静默丢弃。
- **验收**：等助手评测基线完成后，按评测中的 18 个非法执行样例逐一写 RED（合格 RED：编译通过、环境健康、因缺少校验而执行），再 GREEN；评测端到端汇总（`e2e_summary.py`）非法执行为 0。
- **前置**：助手评测基线（另一会话的工作，`assistant-python/eval/`、`AssistantEvalE2EIT` 不属于本轮，未完成前不动）。
- **决定**（2026-11-21）：
  - 原则：Java 能核实的事项一律从数据库独立校验，包括目标评论是本帖反馈且不是助手写的、作者不参与、每人一个资格、商用在素材许可内、奖品是本帖 READY 版本、人数和时间合法。
  - 显式字段只表达解析器才知道的语义：订阅 `action=SUBSCRIBE|CANCEL`、`target=SELF|OTHERS|ALL`，以及 `unsupportedTools`。
  - 校验失败时回复一条原因，任务进入终态 `REJECTED`（新增 V30 放开 CHECK 约束）。目标不是反馈时为 `CLARIFY`。基础设施错误仍重试。
  - CI 增加 pytest job。不改解析器的时间识别。
- **结果**：
  - Java：`AssistantCommandValidationIT` 42 项，RED 38 项失败，GREEN 全部通过。
  - 解析器：识别到不支持的条件时填写 `unsupportedTools`，支持取消、替他人和批量的标记，使用 `parentKind`。pytest 由 RED 22 项失败变为 27 项全部通过。
  - `context` 新增 `parentKind`/`parentIsFeedback`、`nowMs`、`timezone`、`postLicenseCommercial`、`enabledTools`。
  - 端到端非法执行：r3 全量 10 → **0**，r1 冒烟 171 条 18 → **0**。gold 为 EXECUTE 的执行数与基线相同（没有误拦）。
  - 报告：`docs/perf-evidence/assistant/a11-validation-20261121.md`。
  - 收尾：`eval/run_eval.py` 改为发送与 Java 相同的 `context`（取代复跑时用的包装脚本，结果逐字节相同）；V30 合并为一条 `ALTER TABLE`（DROP CHECK 与 ADD CONSTRAINT 在同一语句中）。改写历史后重跑：pytest 27 项通过；单元测试与 8 个 A11 相关 IT 类分批运行，162 用例 0 失败 0 错误 1 跳过（`target/hardening-a/a11-final-c6e8d03-121951`）。

### A12 反馈 resolve 缺 `versionId` 返回 400（A1 认证盘点发现）
- **问题**：`POST /api/materials/{post}/feedback/{comment}/resolve` 直接 `((Number)body.get("versionId")).longValue()`，缺字段 NPE、非数字 ClassCastException，均为 500；并且先解析请求体、后检查身份。
- **验收**：RED/GREEN：缺 `versionId`、`null`、字符串、布尔值，以及非整数 `offsetMs`，都返回 400 `INVALID_REQUEST`；匿名请求仍先返回 401。
- **结果**（`db3789c`）：RED 为 NullPointerException / ClassCastException（500）；GREEN `FeedbackResolveValidationIT`（6 项）通过。

### A13 登录时用户不存在也执行 BCrypt（A1 认证盘点发现）
- **问题**：用户名不存在时登录直接返回 401，不做 BCrypt，响应时间可区分账号是否存在。
- **验收**：RED/GREEN：用户不存在与密码错误两种情况都恰好执行一次 BCrypt（走 A4 线程池），返回相同的 401 `INVALID_CREDENTIALS`。
- **结果**（`8301157`）：用户不存在时对固定的 cost 10 假哈希执行一次校验（与真实哈希同成本、同线程池）。`PasswordHasher.checks()` 在线程池线程内计数，用来证明两条路径都执行了 BCrypt。RED：用户不存在时计数增加 0；GREEN `LoginUnknownUserBcryptIT`（2 项）通过。附带影响：不存在用户的登录现在也占用 BCrypt 线程池容量，与存在用户一致，仍受 A2 的按 IP / 按用户名限流约束。

---

## 第 B 轮：P1 性能
**通用验收**：
- 互动相关项复用 `infra/perf/interactions` 的 a/b/c 场景，每个场景 50/100/200 rps，做改前改后对比。
- 抢购相关项（B4、B5）用 `infra/jmeter/run_dispatch_comparison.py` 的 B2 档，`B2=(batch,20,2)`，与 `docs/perf-evidence/commerce/campaign-lock-v26-*` 同口径。

### B1 同一话题分钟计数热点【需决定】
- **问题**：同一话题、同一分钟的所有互动排在一行 `topic_minute_buckets` 上。
- **证据**：第 1 轮改后，b100/b200/c100/c200 的实际速率仍只有 57–64 rps，几乎每个请求都要等行锁，Hikari pending 183；第 0 轮的 B2 实验定位到这一行分钟桶。
- **先比较两种方案，给推荐，等确认**：
  - (1) 分钟桶分片 `(topic, bucket, shard)`，读时求和
  - (2) 事务内只写 ledger，后台按分钟聚合
  - 对比维度：写路径锁、读路径和窗口查询成本、刷新检测的及时性（R9 的阈值在事务内判断）、R8 撤回语义、重建/清理（R2）、故障恢复、迁移成本。
- **验收**：b/c 场景在 100/200 rps 下错误为 0（或给出新的上限和原因）；桶的净值仍等于成功数；`LikeTopicFlipIT`、`RiskEmitDedupIT`、`TrendingRetentionIT` 全部通过。

### B2 计数改为增量更新 + 离线对账（R3）
- **问题**：`reconcile` 每次跑 4 次 COUNT(*)。已有 100k 赞时 6.7 ms，1M 赞时 65.6 ms（风险清单实测 A）。
- **方案**：根据 `INSERT IGNORE`/`DELETE` 的影响行数做 `±1`；全量 COUNT 改为离线对账任务，小批量执行，能发现并修正偏差，并输出偏差数。
- **待核实**：评论删除、帖子下线等路径是否也会改变计数；对账任务和在线 ±1 并发时的正确性（对账时读计数和写计数需要在同一把行锁下）。
- **验收**：RED 证明每次点赞都会执行 COUNT（用 performance_schema 统计摘要，与 R2 同法）；GREEN 后热路径不再有 COUNT；人为制造偏差后，对账能修正；预置 1M 赞的帖子，单次点赞耗时回到无赞时的水平（与风险清单实测 A 同法）；a/b/c 压测对比。

### B3 每次点赞 3 次连接获取
- **问题**：`visibility.read` 在事务外执行 2 次查询（`db.visible`、`db.authority`），各自获取一次连接，加上事务本身共 3 次。第 1 轮实测 Hikari 获取次数约为请求数的 3 倍；a200 的 p95 是 918 ms，最大 pending 124。
- **方案方向**：把 `visibility.read` 放进互动事务（同一个连接），或者说明为什么不能（例如对锁持有时间的影响、`PostDetailService` 缓存语义）。
- **验收**：每次点赞的连接获取次数为 1（Hikari 计数差值除以请求数）；可见性相关 IT（`VisibilityAndOfflineIT` 等）通过；a/b/c 对比，重点看 a200。

### B4 发送端空轮询退避
- **问题**：发送端每 1 秒（`content.sale.scan-delay-millis`）固定领取一次，没有任务时也照常执行。
- **证据**：`docs/perf-evidence/commerce/mq-dispatch-batch-20261119.md:171`：空领取 B 档 1060 次、C 档 2923 次，每次 4 条 SQL + 1 次提交。调度入口在 `SaleConfiguration.java:107-109`。
- **方案**：连续空领取后指数退避到一个上限；领到任务后，或者有新入队信号时，立即恢复。**不改变租约和确认语义**。
- **待核实**：outbox relay（`RelayConfiguration`）和 feed 投递 scanner 是否也有同样的问题，是否纳入本项。
- **验收**：单元测试覆盖退避序列和恢复；IT 证明退避期间新任务的投递延迟不超过设定上限；B2 档对比空领取次数、每单 SQL/提交次数、端到端延迟。

### B5 抢购缓存同步提交次数【需决定】
- **问题**：缓存同步在每单的提交里占比最大。
- **证据**：`docs/perf-evidence/commerce/campaign-lock-v26-clean-20261120.md:83-91`：落单后固定 2.000 次，加上 bridge 触发的 0.16–0.95 次，合计每单约 2.7–2.8 次提交；`campaign-lock-v26-20261120.md` §6 有优化方向，测算每单降到 2.3–2.6 次提交后约 120–150 单/秒。
- **先给合并或批量方案，说明对缓存一致性和恢复流程的影响，等确认**。
- **前置：压测脚本与 A2 限流（已决定 2026-11-21）**：`seed-commerce-data.ps1`、`refresh_commerce_tokens.py`、`run_ingress_qps.py` 等在造数阶段经 HTTP 注册、登录数百个买家，会被 A2 的注册/登录 IP 限流拦住。
  - **选择**：造数改为直接在独立库中写入账号（`users`、`author_feed_mode`），token 用测试身份密钥在本地签发（与 `infra/perf/interactions` 相同；密钥只放在 `target/`），不再经过 `/api/auth/register`、`/api/auth/login`。
  - **不影响测量结论的理由**：被测应用的配置与生产默认完全相同，没有放宽任何限流；登录/注册本就不在抢购测量窗口内，以前的造数阶段也不在；测量路径（下单、结算、发送）没有任何改动。改动在 B4/B5 采基线之前完成，基线与改后使用同一套脚本。
- **验收**：B2 档对比每单提交次数（`commits_per_order.py`）和吞吐；缓存一致性和恢复相关 IT（`CommerceIngressRevisionIT`、`MaterialCommercePurchaseIT` 等）通过。

### B6 关注潮（R6）与推模式扩散（R7）实测
- **问题**：关注时对被关注作者行 `FOR UPDATE`，大 V 的关注串行；推模式扩散每个粉丝 1 次 SQL 加 1 次 Redis。两者都还没有实测。
- **计划**：
  - R6：独立库，一个作者 + 1000 个账号，4/16/64 并发关注，记录吞吐和等锁时间；同时让该作者发帖，观察发布延迟
  - R7：一个 PUSH 作者分别有 1 万和 10 万粉丝，测发帖到最后一个粉丝 feed 可见的时延，以及 DB/Redis QPS
- **验收**：报告写明问题规模，并给出“本轮修复或推迟”的建议，**等决定**。

---

## 第 C 轮：P2 数据增长

### C1 盘点只增不减的表和 Redis 结构
每一项都要说明：哪些行在什么条件下可以删除或归档、幂等和恢复是否依赖它们、建议的保留期。下表是初步分类，**全部待核实**。

| 结构 | 初步判断 / 待核实点 |
|---|---|
| `outbox_events`、`outbox_replay_audit` | 已发送的行是否还被重放、对账（`PipelineReconcile`）、`consumer_inbox` 校验 payload 时读取（`DistributionInbox.accept` 会读 `outbox_events`）？ |
| `consumer_inbox`、`feed_event_conflicts` | 消费回执用于去重：保留期必须覆盖 MQ 最大重投窗口 |
| `feed_delivery_tasks`（DONE/DEAD）、`delivery_replay_audit` | DEAD 行需要保留给人工重放；DONE 行可删除的条件是什么 |
| `publish_requests` | 发布幂等回执：客户端重试窗口 |
| `material_purchase_requests`、`material_orders`、`material_refund_requests` | 业务记录，**不删除**，只考虑归档 |
| `material_sale_dispatch`（MQ 待发送）、`material_sale_consume_receipts`、`material_sale_cache_jobs`、`material_sale_reservation_fences`、`material_sale_legacy_*`、`material_sale_stream_quarantine` | 已确认（SENT/ACK）的行是否还被恢复流程读取；`ON DELETE CASCADE`（V20）的影响 |
| Redis Stream（`SaleEntryRedis`：`XADD` 没有 `MAXLEN`，只有 `XACK`，从不 `XDEL`/`XTRIM`） | 只能裁剪已确认、并且已经越过最小 pending ID 的条目；与 `XAUTOCLAIM` 接管的关系 |
| `notifications`、`feedback_notify_log`、`assistant_tasks`、`trend_risk_events`、`follow_history`、`media_tasks` | 按业务保留期 |
| `topic_minute_buckets`、`topic_event_ledger` | **已由 R2 处理**（48 h，`TopicRetention`）|
| Redis feed 候选 zset | `FeedTrimmer` 已有裁剪，核实是否覆盖所有键 |

### C2 保留策略与清理任务【需决定】
- 先给出保留策略（每个结构的保留期、删除条件、归档方式），**确认后再实现**。
- 清理任务的要求：小批量短事务，READ COMMITTED（参照 `TopicRetention` 的做法），说明锁影响。
- **验收**：每个清理任务都有测试证明不会删除仍在幂等窗口内或恢复流程需要的数据（例如还在 MQ 重投窗口内的消费回执、未确认的 Stream 条目、DEAD 投递任务）；批大小和间隔可配置；完整 IT 通过。

---

## 第 D 轮：P3 测试缺口

### D1 CI 覆盖【需决定】
- **现状**：`.github/workflows/ci.yml` 运行 `mvn -Pintegration verify`，**`timeout-minutes: 20`**。本地全量跑了 29.5 分钟，CI 的历史运行约 11 分钟。
- **临时措施（2026-11-21）**：run `44866500185`（`3ca91d8`）的 maven job 跑了 16 分钟（04:31:14Z–04:47:15Z），接近 20 分钟上限，因此把 `timeout-minutes` 调到 **35**。这只是临时放宽，不是 D1 的结论；是否拆 job、是否收紧超时，仍按下面的评估决定。
- **跳过项（本地全量，`3610fa7`）**：集成测试 93 个 + 单元测试 1 个

| 测试 | 跳过数 | 原因 |
|---|---|---|
| `CommerceDispatchBatchRealMqIT` | 52 | 需要 `CONTENT_REAL_MQ_IT=true` |
| `CommerceRealMqIT` | 39 | 同上 |
| `MediaMinioIT` | 1 | 需要 `-Dcontent.minio.it=true`；**没有可维护的官方 MinIO 镜像或二进制**（`docs/m4-m6-batch.md:30`），限制照旧 |
| `PerfHarnessIT` | 1 | 需要 `-Dcontent.perf.enabled=true`（性能实验，不进 CI）|
| `FfmpegTranscoderExecTest`（单元） | 1 | 跳过原因待核实（预计依赖 docker/ffmpeg 镜像）|

- **待评估**：真实 MQ 测试设置环境变量后能否在 GitHub Actions 上用 Testcontainers 的 RocketMQ 运行，以及新增的耗时；在 runner 上安装 FFmpeg 或拉取镜像；是否需要拆成多个 job，或者调整 20 分钟的超时。
- **验收**：按收益和 CI 耗时给出建议，**确认后再改 workflow**；改后的 CI 运行通过，并列出仍然跳过的项和原因。

### D2 FFmpeg 超时后的容器清理
- **问题**：`FfmpegTranscoder` 用 `docker run --rm` 执行（`FfmpegTranscoder.java:72`）。超时后只杀掉本地 docker CLI 进程，容器可能还在运行（CLAUDE.md 列为“已知边界：未验证”）。
- **验收**：测试制造超时（例如用一个持续 sleep 的命令或很短的超时），断言超时后容器确实被停止并删除（`docker ps -a` 按名字或标签过滤后为空）；如果没有删除，就修复（例如给容器加 `--name`，超时后执行 `docker rm -f`），并按 RED/GREEN 记录。

### D3 `MediaFfmpegIT.offlineDuringProcessingFailsLeaseWithoutCompleting` 偶发失败
- **位置**：`MediaFfmpegIT.java:105`
- **待核实**：收集历史失败日志（CI artifact、本地），重复运行 N 次统计失败率，判断是测试时序问题（等待、屏障不足）还是真实竞态（下线与租约完成之间）。
- **验收**：写明根因；修复对应的一方（测试或生产代码；如果是生产竞态，按 RED/GREEN）；修复后连续运行 ≥30 次无失败。

### D4 前端话题输入
- **问题**：R1 之后话题由作者设置，但前端没有输入入口，话题页上线后为空。
- **验收**：发帖和编辑页面加话题输入框（可选，最长 128 字符，与后端校验一致，编辑时可以清除）；`frontend` 下 `npm test`（vitest）全部通过，修复已有失败项并在报告中列出。
- **追加（2026-11-21，A10 附带发现）前端评论幂等键**：
  - 问题：`pages/posts/[id].vue` 用 `ui-${Date.now()}`、`assistant-${Date.now()}`，`pages/hot-topics/[topic]/[post].vue` 用 `web-${postId}-${Date.now()}`，每次点击生成新键，网络重试不会复用，后端幂等对用户重试不起作用。
  - 要求：打开评论框时生成一次键（随机 UUID），发送失败重试时复用，发送成功后才换新键；后端已有幂等，无需改动。
  - 验收：补一个前端测试（vitest），证明同一次评论的重试使用同一个键，成功后下一条评论使用新键。

### D5 支付与退款仍为模拟实现
- 不接入真实支付。在 README 和相关设计文档里如实说明：支付和退款是模拟实现，哪些状态流转是模拟的，接入真实支付前需要补哪些东西（回调验签、对账、幂等）。
- **验收**：文档更新已提交；代码中没有任何地方暗示接入了真实支付。

---

## 变更记录
| 日期 | 轮次 | 内容 |
|---|---|---|
| 2026-11-21 | 计划 | 建立本计划；上一轮收尾提交 `0b9d5b7`、`eddba0c` 并推送，CI 通过 |
| 2026-11-21 | 计划 | 计划确认；A2、A3 设计已决定（见各项），A1 追加全仓接口认证检查 |
| 2026-11-21 | A | A1–A6 已修复（`0480365`…`982e742`）；全量 IT 因系统内存不足被中止，待重跑；新增 A7–A10（A1 扫描发现）；记录抢购压测脚本的造数方式决定（B5 前置） |
| 2026-11-21 | A | A7（`89f9816`）、A8（`88668db`）、A9（`056a65a`、`3627911`）已修复；A10 已复现（`aa75ca7`），结论与方案待决定 |
| 2026-11-21 | A | A10 采用方案 1 并修复（`b0d31e0`）；新增 A11（等助手评测基线）；前端评论幂等键并入 D4；完整 IT 改为分批运行（每批 1–2 个测试类，failsafe -Xmx1g，批前可用内存 ≥3GB，批后确认容器退出） |
| 2026-11-21 | A | 第 A 轮收尾：分批完整 IT 全部通过（`1535a09`，488 用例 0 失败 0 错误 94 跳过，b03 起在重启后续跑）；互动 a/b/c 复测与登录前后压测完成，报告 `docs/perf-evidence/hardening-a-20261121/`；A1–A10 标为完成，A4 遗留池大小默认值待决定，A11 等助手评测基线 |
| 2026-11-21 | A | A4 池大小默认值改为 max(2, 逻辑核数/2)（`0d171dc`）；新增并修复 A12（`db3789c`）、A13（`8301157`）；相关测试分批运行全部通过（单元测试 + 11 个 IT 类，94 用例 0 失败 0 错误 1 跳过，`target/hardening-a/it-batches-related-8301157-093435`）；删除 target/ 下三个旧 worktree（测试报告已归档到 `target/hardening-a/worktree-reports/`） |
| 2026-11-21 | A | A11 完成：Java 端独立校验订阅和抽奖，校验失败进入 REJECTED 并回复原因（V30）；解析器上报不支持的条件，并标出取消、替他人、批量；`context` 补全；CI 加 pytest；端到端非法执行 r3 10→0、冒烟 18→0；LATEST_BASELINE 指向 r3 |
