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
| C1 | 只增不减的表和 Redis 结构盘点 | P2 | 完成（基于 `703ac60`） |
| C2 | 保留策略与清理任务（已决定） | P2 | 进行中（已推送，待 CI） |
| C3 | 抢购 Redis 状态哈希随全部请求增长（C-10） | P1 | 未开始 |
| D1 | CI 跳过项评估与启用（已决定） | P3 | 完成（CI run 44941020312 通过） |
| D2 | FFmpeg 超时后的容器清理测试 | P3 | 完成 |
| D3 | `offlineDuringProcessingFailsLeaseWithoutCompleting` 偶发失败 | P3 | 完成（连续 10 次，置信度有限） |
| D4 | 前端话题输入框 + 前端测试 | P3 | 完成 |
| D5 | 支付/退款为模拟实现的文档说明 | P3 | 完成 |
| D6 | FFmpeg 容器创建前超时的残留（D2 遗留） | P3 | 未开始 |
| E1 | @助手 接入大模型解析器（多供应商、兜底、注入防护） | — | 完成 |

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
每一项都要说明：哪些行在什么条件下可以删除或归档、幂等和恢复是否依赖它们、建议的保留期。

**结果（2026-11-22，基于 `703ac60` 只读核实）**。保留原则（用户给定）：业务记录不删；幂等和消费回执默认 7 天；已完成的中间态默认 7 天；DEAD 和人工处理记录不自动删；Redis Stream 只裁剪已确认且早于最小 pending 的条目，默认 1 天；通知等默认 180 天；为 B1 预留“只删除已处理数据”的约束。

**关键结论**
1. **Feed 链路三张表必须按事件一起删，顺序 outbox → inbox → DONE 任务（同一事务）**。`DistributionInbox.accept` 先读 `outbox_events`，缺失即判 `OUTBOX_EVENT_CONFLICT`（`DistributionInbox.java:32-33`）；`PipelineReconcile` 会为“SENT 无 inbox”补 inbox、为“inbox=PUSH/HISTORY 无任务”从 outbox payload 补任务（`PipelineReconcile.java:61-74,90-99`）。若先删 DONE 任务而 inbox 仍在，对账会重建任务、**重新扇出旧帖子**。按 outbox → inbox → 任务的顺序，任一中间态最多让 `orphanTasks`（只检测不修改）误报。
2. **抢购 Redis 状态哈希 `sale:{entry-v2}:state` 随全部购买请求增长，由设计决定**：恢复时从全部 `material_purchase_requests` 重建（`SaleIngress.java:137-138`，`SaleIngressMapper.java:69` 无过滤）。`k:`（用户+clientKey → 请求，跨活动）不可删：删除后用户在新活动重用 clientKey，Redis 放行、MySQL `uq_purchase_client` 冲突 → `CLIENT_KEY_CONFLICT` 隔离 → 活动暂停。可安全清理的只有 `t:<streamId>` 和失败恢复留下的 `stage:<gen>`。
3. **`material_sale_cache_jobs` 不可删**：删除后行从 `revision=1` 重新开始，而 Redis 仍有更大的 `v:<kind>:<key>`，APPLY 脚本把它当作“已应用”直接返回 1，缓存更新被静默丢弃（`SaleEntryRedis.java:208-210`，`SaleIngressMapper.java:56`）。
4. **`media_tasks` 的 DONE 行是媒体播放的数据源**（`MediaService.java:70,77` 读 output/cover），属于业务数据。
5. **`follow_history` 是可见性授权数据**（`FeedMapper.java:40`、`RecoveryMapper.java:26`、`DeliveryMapper.java:32`），不能按时间删，只能删失效 epoch（重新关注 epoch 必 +1，`RelationshipService.java:24`）。
6. **MQ 重投窗口依赖 broker 默认值**：`infra/docker/broker.conf` 未设 `fileReservedTime`（默认 72 h），consumer 为 `CONSUME_FROM_FIRST_OFFSET`（`PipelineConsumer.java:22`），位点丢失或 `repair-broker-store.ps1` 后可重放最多 72 h；`maxReconsumeTimes` 默认 16（约 4 h 46 min）后进 DLQ（DLQ 同样 72 h）。回执 7 天以此为前提。生产 broker 实际值：缺证据。
7. Feed 候选 zset 覆盖已核实：所有写入目标都是 follower，关注时必 `initGraph`（`RelationshipService.java:21`），`FeedTrimmer` 遍历 `follow_graph_version` 覆盖全部键；Redis 写入需要 `feed:write-generation`（仅恢复流程设置），`feed_control` 只按 PRIMARY→REBUILDING→READY 转换，Trimmer 只在 READY 运行不留缺口。没有条数上限，`:untrusted` 标记只在恢复时 UNLINK（量很小）。

**Feed / Outbox**

| 结构 | 可删条件 | 依赖流程（代码位置） | 建议 |
|---|---|---|---|
| `outbox_events` | `status='SENT'` 且 `sent_at` 早于截止 且 有 `consumer_inbox` 回执 且 无任务或任务为 DONE 且 无 `outbox_replay_audit`（FK，V3）且 无 `feed_event_conflicts` | inbox 校验 `DistributionInbox.java:32`；对账 `PipelineReconcile.java:61,66-70`；DEAD 重放 `RelayMapper.java:25`；`uq_outbox_post_published` 对发布只是双保险（真正的保护是 `posts.status='DRAFT'`，`PublishMapper.java:30`）| SENT 7 天；NEW/RUNNING/DEAD 不删；SENT 但无 inbox 超过 7 天是对账缺口，保留 |
| `consumer_inbox` | 与 outbox 同事务；事件有 DEAD 任务则保留（否则对账报 orphan） | 去重 `DistributionInbox.java:35`；对账三项扫描 | 7 天，且须 ≥ broker 保留期 + 余量 |
| `feed_delivery_tasks` | 只删 DONE，与 inbox 同事务；**无完成时间列**（`last_attempt_at` 是领取时间） | `uq_delivery_event` 是建任务的幂等约束（`InboxMapper.java:13`）；DEAD 重放 `DeliveryMapper.java:36` | DONE 7 天；DEAD 不删 |
| `outbox_replay_audit`、`delivery_replay_audit`、`feed_recovery_audit` | 人工操作记录 | — | 不删 |
| `feed_event_conflicts` | 人工对账记录，**无“已处理”状态列** | 写入 `DistributionInbox.java:61` | 不删 |
| `follow_history` | 同一 (follower, followee) 的当前 `follow_epoch` 更大，或当前状态 UNFOLLOWED | 读取都 JOIN `follows` 要求 epoch 一致且 FOLLOWING | 只删失效 epoch，不按时间 |
| `feed_mode_transitions` | 只读 VERIFYING（`ModeMapper.java:11`），量小 | — | 不删 |
| `publish_requests` | **无时间列**，只能 JOIN `posts.published_at_ms` | 重试读回执 `PublishTransaction.java:37-41`；删除后同键重试返回 409 `POST_STATE_CONFLICT` 而不是原快照，不会重复发布 | 见【需决定】C-7 |

**抢购（MySQL）**

| 结构 | 可删条件 | 依赖流程 | 建议 |
|---|---|---|---|
| `material_purchase_requests`、`material_orders`、`material_refund_requests` | 业务记录 | — | 不删 |
| `material_sale_dispatch` | `state='SENT'` 且 `sent_at_ms` 早于截止 且 请求为终态；V23 迁移的旧行 `sent_at_ms` 可能为 NULL | Stream 条目重处理时会对已存在请求再 enqueue（`SaleBridgeBatch.java:94,97`）：行已删就多发一条 MQ，消费端因请求非 PROCESSING 忽略（`SaleService.java:197`），无害 | SENT 7 天 |
| `material_sale_consume_receipts` | 请求为终态 | 结算主要靠请求状态幂等（`SaleService.java:197,251`），回执是第二道保护 | 7 天 |
| `material_sale_cache_jobs` | 不可删（见结论 3） | `SaleEntryRedis.java:208-210` | 不删 |
| `material_sale_reservation_fences` | BLOCKED 是隔离决定；ALLOW 每请求一行，可重建，但与 `block()` 竞争（`SaleBridgeMapper.java:25-26`），`reconcileReleases` JOIN 它（`:41`） | — | 不删（收益低） |
| `material_sale_stream_quarantine` | HOLD/RELEASE_PENDING 未处理；RELEASED/NO_RELEASE 终态；恢复时全量分页读（`SaleBridgeBatch.java:147-155`） | — | 不删（人工处理记录） |
| `material_sale_legacy_replays` | 审计 | — | 不删 |
| `material_sale_legacy_streams`、`account_material_decision_locks`、`trend_topic_serial` | 游标行/锁行，删除与加锁竞争 | `SaleMapper.java:224-229` | 不删 |
| V20 `ON DELETE CASCADE`（dispatch → request） | 只在删除请求时触发；业务记录不删，当前不起作用。将来若归档请求：dispatch 会被静默级联删除，receipts、orders 的 FK 会阻止删除 | — | 记录 |

**通知与其他业务表**

| 结构 | 判断 | 建议 |
|---|---|---|
| `notifications` | 删除后 `uq_notif_dedupe`、`uq_notif_event_recipient` 去重失效；触发源都是一次性状态变化（反馈通知除外，见下行） | 180 天 |
| `feedback_notify_log` | 作者可随时再次 `linkResolution`，每次都走 `maybeNotify`（`FeedbackWatchService.java:53-65,78`）；log 与通知都删掉后再次 resolve 会重复通知 | 见【需决定】C-4 |
| `assistant_tasks` | `GET /api/assistant/tasks/{id}`（A8）读取；`uq_assistant_trigger` 去重 | 见【需决定】C-2 |
| `trend_risk_events` | `uq_risk_business` 只在当前窗口有意义；`ix_risk_time` 时间前导索引已有 | 180 天 |
| `media_tasks` | DONE 是业务数据，DEAD 人工处理 | 不删 |
| `post_reports`、`giveaway_*`、`material_entitlements`、点赞/收藏等 | 业务与证据 | 不删 |
| `topic_minute_buckets`、`topic_event_ledger` | 已由 `TopicRetention` 48 h 清理（R2） | 加 B1 水位约束（C-13） |

**Redis**

| 结构 | 现状 | 建议 |
|---|---|---|
| `sale:{entry-v2}:stream`（组 `sale-persist-v2`） | 核实：`XADD` 无 MAXLEN，从不 XTRIM/XDEL。正常条目在 MySQL 提交后 ACK（`SaleBridgeBatch.java:65`），隔离条目复制 payload 入表后 ACK（`:133`）；ACK 后条目内容无人再读 | Lua 原子 `XTRIM MINID`，边界 = min(最小 pending ID, last-delivered-id 的下一个, now−1 天)；XAUTOCLAIM 只接管 pending，不受影响 |
| state 哈希 `t:<streamId>` | 每条目一个字段，从不删除（计划原表未列） | 随 XTRIM 一起 HDEL，**排除**隔离状态 HOLD/RELEASE_PENDING 的条目（释放脚本比对 `t:`，`SaleEntryRedis.java:143`，缺失会永远停在 RELEASE_PENDING、活动持续暂停） |
| state 哈希 `r:`、`k:`、`o:`、`refund:`、`released:`、`v:` | 随业务记录增长，恢复全量重建；约 591–653 B/请求（见 C3） | 不清理，另立 C3 |
| `sale:{entry-v2}:stage:<gen>` | 恢复中途失败留下一份完整大小的哈希，无清理（`SaleEntryRedis.java:179`） | 删除 generation < 当前且不属于进行中恢复的 stage |
| `quarantine-stream:<gen>`、`quarantine-control:<gen>` | 仅在 key 类型异常时由 rename 产生 | 人工处理 |
| 旧版 `sale:{<id>}:pending`、`sale:{<id>}:r:*` | 每次恢复从游标读（`SaleIngress.java:152-165`） | 见【需决定】C-11 |
| `feed:v2:{reader}:inbox` | 见结论 7 | 不改 |
| `rl:v1:*`、`post:detail:v1:*`、`trend:v1:net:*` | 有 PEXPIRE / SETEX 300 / 重建前 DEL | 不需要处理 |

### C2 保留策略与清理任务（已决定）
- 先给出保留策略（每个结构的保留期、删除条件、归档方式），**确认后再实现**。
- 清理任务的要求：小批量短事务，READ COMMITTED（参照 `TopicRetention` 的做法），说明锁影响。
- **验收**：每个清理任务都有测试证明不会删除仍在幂等窗口内或恢复流程需要的数据（例如还在 MQ 重投窗口内的消费回执、未确认的 Stream 条目、DEAD 投递任务）；批大小和间隔可配置；完整 IT 通过。

**清理任务**：每个任务独立开关、批大小、间隔、保留期；每批一个 READ COMMITTED 短事务，按主键顺序取一批 id 再按 id 删除，只对被删行加记录锁、不加间隙锁，与在线写入只可能在已过期的行上相遇，最多等一批。

| 任务 | 删除内容 | 默认保留 |
|---|---|---|
| R-FEED | 同事务删除 outbox(SENT) → inbox → DONE 任务，条件见 C1 表 | 7 天 |
| R-FOLLOW | `follow_history` 失效 epoch | 不按时间 |
| R-SALE | `material_sale_dispatch`(SENT, 请求终态)、`material_sale_consume_receipts`(请求终态) | 7 天 |
| R-NOTIFY | `notifications`、`trend_risk_events`（`feedback_notify_log` 按 C-4） | 180 天 |
| R-STREAM | Stream 中已确认条目按 id `XDEL`（边界同 `XTRIM MINID` 的计算）+ 对应 `t:` HDEL（排除隔离未处理）+ 孤儿 `stage:<gen>` | 1 天 |
| R-TOPIC | 现有 `TopicRetention` 增加“已处理水位”上限（B1 预留，默认无水位 = 现行为） | 48 h |

**决定（2026-11-22）**：C-1…C-14 全部按推荐执行；补充：C-10 另立 C3（P1）并先补证据；C-6 的旧行保留，记录数量（见下方结果），以后可做一次性人工清理。下面保留各项原文。
- **C-1 `media_tasks`**：计划原表把它放在“按业务保留期”，但 DONE 是业务数据。推荐：不删。
- **C-2 `assistant_tasks`**：(a) 视为业务记录不删；(b) 终态保留 180 天后删除，之后查询接口返回 404。推荐 (a)。
- **C-3 `follow_history`**：推荐只删失效 epoch，不适用 180 天。
- **C-4 `feedback_notify_log`**：与“幂等 7 天”冲突。推荐：保留 180 天，且只在对应通知（`dedupe_key='feedback-ready:<account>:<comment>:<version>'`）已删除时才删；或接受过期后可能重复通知。
- **C-5 `feed_delivery_tasks` 缺完成时间**：推荐新增迁移加 `completed_at`（置 DONE 时写入）；已有 DONE 旧行（`completed_at` 为 NULL）用 `last_attempt_at` 近似判断。
- **C-6 时间为 NULL 的旧行**（outbox SENT 而 `sent_at` NULL；dispatch SENT 而 `sent_at_ms` NULL）：推荐保留不删，不推算时间。
- **C-7 `publish_requests`**：每帖一行，与 `posts` 同量级；按 7 天删需 JOIN 发布时间，且过期后重试从返回原快照变为 409。推荐：不删。
- **C-8 `material_sale_cache_jobs`、fences ALLOW、锁行**：推荐不删。
- **C-9 `feed_event_conflicts`**：无“已处理”状态，永远无法清理。推荐本轮不改，只记录；需要时再加 `resolved_at` 与运维命令。
- **C-10 抢购 Redis 状态哈希**：只靠清理解决不了（内存和恢复耗时随全部请求线性增长）。推荐：本轮不处理，另开设计项（例如只为未结束活动加载 `r:`，`k:` 必须保留），先补单请求内存占用的证据。
- **C-11 旧版每活动 Stream/哈希**：推荐本轮不处理（新环境无此类数据）。
- **C-12 Stream 1 天裁剪 + `t:` + 孤儿 `stage:<gen>`**：推荐纳入本轮。
- **C-13 B1 约束**：推荐在 `TopicRetention` 加“已处理水位”接口：ledger 截止点 = min(now−48h, 水位)（余量由提供水位的一方扣除），默认无水位（现行为）；B1 选方案 (2) 时由后台聚合器提供水位。撤回点赞立即删 ledger 行（`InteractionMapper.java:65`）在方案 (2) 下的语义留给 B1 决定。
- **C-14 MQ 保留期**：推荐在 `infra/docker/broker.conf` 显式写 `fileReservedTime=72`，应用侧增加配置 `content.retention.broker-reserved-hours`（默认 72），启动时校验回执保留期 ≥ 它 + 24 h，不满足则拒绝启动。

**结果（`a8758cd`、`6966dc6`、`c536878`；C3 临时措施 `2c665b8`）**
- **实现**：`FeedRetention`（R-FEED）、`FollowHistoryRetention`（R-FOLLOW）、`SaleRetention`（R-SALE）、`NotificationRetention`（R-NOTIFY）、`SaleStreamRetention`（R-STREAM，脚本在 `SaleEntryRedis.acknowledgedBefore/dropAcknowledged`）、`TopicRetention` 增加已处理水位（C-13，默认 `ALL_PROCESSED` = 现行为）；SQL 集中在 `RetentionMapper`；`RetentionConfiguration` 为每个任务建一个守护线程。V31 给 `feed_delivery_tasks` 加 `completed_at`，`DeliveryMapper.progress` 置 DONE 时写入（C-5）。`infra/docker/broker.conf` 显式 `fileReservedTime=72`，`RetentionBounds.requireCoversBroker` 在启动时校验回执保留期 ≥ broker 保留期 + 24 h（C-14）。
- **配置**（`content.retention.<job>.*`，job = `feed`、`follow-history`、`notifications`、`sale`、`sale-stream`）：`enabled`（默认 true）、`batch`（500；Stream 上限 1000）、`max-batches`（每次运行最多批数，200）、`interval-millis`（600000，最小 60 s，首次运行在启动后一个间隔）、`retention-millis`（feed/sale 7 天，notifications 180 天，sale-stream 1 天）；`content.retention.broker-reserved-hours`（72）。sale、sale-stream 只在 `content.media.enabled=true`（抢购启用）时创建。保留期对象总会创建，所以即使关掉任务，不安全的保留期也会让启动失败。
- **锁影响**：每批一个 READ COMMITTED 短事务（复用 `feedTransactions`，超时 15 s）。候选用普通读从索引取（outbox 走 `ix_outbox_retry`，dispatch 走 `ix_sale_dispatch_pending_due`，二者都利用“下次重试/可用时间不晚于发送时间”，不给热表加新索引），批内用 `FOR UPDATE` 重新校验后按主键删除：只对选中的行加记录锁，不加间隙锁。在线写路径不碰这些行（relay 只领 NEW/RUNNING、replay 只动 DEAD、投递 worker 只领 NEW/RUNNING、发送端只领 PENDING/SENDING），冲突最多等一批。R-FOLLOW 的多表 DELETE 会对同一对 (follower, followee) 的 follows 行加一批时长的共享锁。R-SALE 用 `FOR UPDATE OF d,c`，不锁请求行。Redis 每步是一个原子脚本，不在 SQL 事务里做 Redis I/O。
- **已知限制**：R-FEED/R-SALE 每次运行从索引起点扫描，长期不可删的行（对账缺口、DEAD、审计、冲突）排在前面会占用批次，超过 `batch × max-batches` 时后面的行要等异常行处理掉；R-NOTIFY 遇到第一条未过期的通知就停，排在较新 id 之后的旧行要等前面的行过期；被保留的隔离条目的 `t:` 在其释放后不会被本任务删除，直到下次恢复重建状态。
- **C-6 计数**（本地开发库，2026-11-22 只读查询，库在 V26；生产数量缺证据）：`outbox_events` SENT 且 `sent_at` 为 NULL：0 / 39,912；`material_sale_dispatch` SENT 且 `sent_at_ms` 为 NULL：0 / 70,702。另有没有 dispatch 行的消费回执（V20 之前的数据，R-SALE 不处理）：10,210 / 80,912。这些行保留，以后可以做一次性人工清理（确认请求为终态、超过保留期后按主键删除）。统计 SQL 在 `RetentionMapper` 末尾。
- **测试**（RED → GREEN，分批脚本）：
  - RED（骨架编译通过、容器正常、因缺少行为失败）：`target/hardening-c/red-052247`（`FeedRetentionIT` 6/6 失败）、`red-NotificationRetentionIT-052514`（1/1）、`red-SaleRetentionIT-052716`（3/4；`streamRetentionWithoutConsumerGroupTrimsNothing` 是守卫用例，空实现下即通过）。首个 RED 尝试 `red-052206` 是测试代码编译错误（`XAddArgs.Builder.id` 不存在），不算 RED，日志保留。
  - GREEN：`target/hardening-c/green-053218`（`FeedRetentionIT` 6/6；同批 `MySqlMigrationIT` 因迁移数断言仍为 30 失败，更新为 31 后通过）、`green-053612`（`MySqlMigrationIT`、`NotificationRetentionIT`、`SaleRetentionIT` 0 失败）。
  - 覆盖的“不会误删”：7 天内的回执、SENT 无回执、PUSH/HISTORY 回执无任务、DEAD/RUNNING/新近 DONE 任务、DEAD/NEW outbox、`sent_at` 为 NULL、有重放审计或冲突记录的事件；未结算请求、新近回执（dispatch 与回执同删同留）、PENDING/SENDING、无 dispatch 的回执；pending 及其之后的 Stream 条目、未投递条目、1 天内条目、无消费组时不裁剪、隔离未处理条目的 `t:`；正在进行的恢复的 stage；当前 epoch 的 follow_history；仍有通知的 feedback log；B1 水位之后的 ledger。删除后对账三项（orphanTasks、任务缺口、回执缺口）不变。
  - 完整分批 IT：`it-full-054219` 跑完 unit 与 b01 后被系统以内存不足为由停止（非测试失败）；按决定完整验证交给 CI。报告：`docs/perf-evidence/hardening-c-20261122/README.md`。

### C3 抢购 Redis 状态哈希随全部请求增长（C-10）
- **问题**：`sale:{entry-v2}:state` 为每个购买请求保存 `r:`（请求 JSON）、`k:`（用户 + clientKey → 请求，跨活动）、`o:`（已购归属），结算后还有 `v:REQUEST:*`、`v:OWNER:*`（直到下次恢复）。恢复时从全部 `material_purchase_requests` 重建（`SaleIngress.java:137-138`，`SaleIngressMapper.java:69` 无过滤），所以内存和恢复时间都随历史请求总数线性增长，C2 的清理无法缩减（只清 `t:` 和孤儿 stage）。
- **证据（本地开发 Redis，2026-11-22 只读测量；往期压测数据，80,912 个请求）**：
  - 哈希 333,459 个字段，`MEMORY USAGE … SAMPLES 0` = 62,643,215 B，编码 hashtable。按前缀：`r:` 80,912、`k:` 80,912、`o:` 80,758、`v:` 59,521、`t:` 29,748、`c:` 1,485、`refund:` 120。
  - 字段名 + 值的原始字节共 44,454,460 B，其余 18,188,755 B 按字段均摊为每字段约 54.5 B 开销（dictEntry、sds 头、分配器取整、桶数组）。
  - **每个请求约 591 B**（恢复后形态：`r:` + `k:` + `o:`，原始 427 B + 3 个字段开销）；带上结算后的 `v:` 字段约 **653 B**。另外每个未裁剪的 Stream 条目约 273 B，加上它的 `t:` 约 325 B（C2 后 1 天内清掉）。
  - 方法：用 Lua `HSCAN` 统计各前缀字段数和字节数，用 `MEMORY USAGE` 总量反推每字段开销。是对真实数据的平均估算，不是逐字段精确值。
- **容量推算**：本地 compose 的 Redis **未设置 maxmemory**（`CONFIG GET maxmemory` = 0），策略 `noeviction`，`appendonly yes`；Docker Desktop VM 总内存 4.80 GiB（与 MySQL、RocketMQ、MinIO 共用），当前 `used_memory` 81.2 MB。
  - 每 1 GiB 可容纳约 **164 万**个请求（按 653 B）或约 182 万个（按 591 B）。
  - 如果 Redis 独占整个 VM（不现实的上限）约 776 万个；AOF 重写 fork 的写时复制最坏需要接近两倍内存，实际上限更低。其他容器占用和生产配置：缺证据。
- **风险**：设置了 maxmemory 时，`noeviction` 下写满后，下单入口 Lua（`HSET`/`XADD`）返回 OOM 错误，抢购入口失败，恢复重建 stage 也会失败（恢复需要额外一份完整大小的 stage 哈希，峰值约为状态哈希的两倍）。**目前未设置 maxmemory**，写满时不会得到干净的写入错误，而是由容器或 VM 内存耗尽触发 OOM kill，影响更大。恢复时间同样随请求总数线性增长（数值缺证据）。
- **临时措施（2026-11-22，已做）**：开发用 compose 的 Redis 显式设置 `--maxmemory 1gb`，保留 `noeviction`（`infra/docker/compose.yml`；Docker Desktop VM 4.80 GiB 与 MySQL、RocketMQ、MinIO 共用，AOF 重写 fork 另需余量）。验证：`start.ps1` 后四个服务 healthy，`CONFIG GET maxmemory` = 1073741824；临时把上限调到 1mb 时，写入脚本返回 `OOM command not allowed when used memory > 'maxmemory'`，探测 key 没有写入，随后恢复 1gb。入口代码 `SaleEntryRedis.reserve` 抛出的 `RedisException` 由 `SaleService.java:101` 转成 **503 `SALE_CACHE_UNAVAILABLE`**（这一映射是读代码确认，没有在写满状态下实际发 HTTP 请求）。注意：恢复流程需要再建一份完整大小的 stage 哈希，所以接近上限时恢复也会失败；broker 运行时 `fileReservedTime` 确认是 72（RocketMQ 镜像自带的默认 conf 写的是 48，部署时不要用它替换本项目的 conf）。**这只把写满变成可观测的写入失败，根治方向（下面 2–5）仍未实现**；生产环境的 maxmemory 和告警需要另行配置。
- **可能方向**（待设计，需决定）：
  1. 给 Redis 设置 maxmemory 和告警（例如用量超过 70%），先让写满变成可观测、可预期的失败；
  2. 已终结请求的状态迁出 Redis：活动结束后 `r:`、`o:` 只保留在 MySQL，入口对未知请求回源 MySQL；
  3. 按活动结束时间过期：恢复只加载未结束（含宽限期）活动的 `r:`、`u:`、`o:`；
  4. `k:` 是跨活动的 clientKey 唯一性，**不能简单删除**（会导致新活动中重用 clientKey 被 Redis 放行、MySQL 冲突、隔离并暂停活动），需要改成按活动作用域或回源 MySQL 判定，涉及入口语义；
  5. `v:` 字段可以在 cache job 应用后清除，但要和 `material_sale_cache_jobs` 的版本号协议一起设计（见 C1 结论 3）。
- **验收**（草案）：给出方案对比和推荐，等决定；实现后用同样的方法测量，单请求常驻字节数下降，并且恢复时间不再随历史请求总数增长。

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
- **决定（2026-11-21）**：拆成并行 job，每个超时 ≤35 min，超了再拆、不调大；真实 MQ 用 Testcontainers 并设 `CONTENT_REAL_MQ_IT=true`；FFmpeg 测试尽量在 CI 运行；MinIO 照旧跳过；PerfHarnessIT 不进 CI；`setup-java` 升 v5。
- **结果（`2ec9483`、`5d93a28`；CI run `44941020312` 通过）**：job 为 `frontend`（15 min）、`assistant-python`、`maven (a-l)`、`maven (m-z)`（按 IT 类名首字母拆分）、`maven-real-mq`。CI 实际耗时 26 s / 16 s / 7m44s / 8m52s / 3m07s，均远低于上限；真实 MQ 95/95 实际运行。真实 MQ 套件改用 Testcontainers（`CommerceMqFixture`，broker 端口 30924），本地同一 JVM 95/95 通过、231 s。FFmpeg 经 Docker 镜像运行，不需要在 runner 上装宿主机 ffmpeg（此前已在 CI 运行）。本地完整分批 547 运行 / 0 失败 / 94 跳过，Maven 时间合计 39.7 min，两个分片约 19.0 / 19.9 min（分批口径偏高）。actionlint 0 错误。仍跳过：MediaMinioIT、PerfHarnessIT、AssistantEvalE2EIT（评测工具）、FfmpegTranscoderExecTest 和 WindowsListenBacklogTest 的 Windows 用例。报告：`docs/perf-evidence/hardening-d-20261122/README.md`。

### D2 FFmpeg 超时后的容器清理
- **问题**：`FfmpegTranscoder` 用 `docker run --rm` 执行（`FfmpegTranscoder.java:72`）。超时后只杀掉本地 docker CLI 进程，容器可能还在运行（CLAUDE.md 列为“已知边界：未验证”）。
- **验收**：测试制造超时（例如用一个持续 sleep 的命令或很短的超时），断言超时后容器确实被停止并删除（`docker ps -a` 按名字或标签过滤后为空）；如果没有删除，就修复（例如给容器加 `--name`，超时后执行 `docker rm -f`），并按 RED/GREEN 记录。
- **结果（`90452ce`）**：RED 确认超时后容器仍在运行；修复为唯一 `--name` + 标签，超时或中断时 `docker rm -f`。`FfmpegTranscoderTimeoutIT` 超时、中断两个用例通过（中断用例无单独 RED）。遗留：容器尚未创建时超时的理论窗口。报告：`docs/perf-evidence/hardening-d-20261122/README.md`。

### D3 `MediaFfmpegIT.offlineDuringProcessingFailsLeaseWithoutCompleting` 偶发失败
- **位置**：`MediaFfmpegIT.java:105`
- **待核实**：收集历史失败日志（CI artifact、本地），重复运行 N 次统计失败率，判断是测试时序问题（等待、屏障不足）还是真实竞态（下线与租约完成之间）。
- **验收**：写明根因；修复对应的一方（测试或生产代码；如果是生产竞态，按 RED/GREEN）；修复后连续运行 ≥30 次无失败。（2026-11-21 调整为连续 10 次，并在报告中说明置信度有限。）
- **结果（`ba2cc30`）**：根因是测试夹具竞态：`MediaFfmpegIT` 没有关闭 1 s 后台媒体扫描，扫描线程可能抢在测试的 `tick()` 之前认领任务；生产代码的租约和版本检查正确。RED 守卫用例证明 3 s 内任务被后台处理为 DONE。修复：`content.media.scan-delay-millis=3600000`（与其他 media IT 一致），`MediaPipelineIT` 预防性同改。修复后连续 10 次 0 失败；10 次置信度有限。报告：`docs/perf-evidence/hardening-d-20261122/README.md`。

### D4 前端话题输入
- **问题**：R1 之后话题由作者设置，但前端没有输入入口，话题页上线后为空。
- **验收**：发帖和编辑页面加话题输入框（可选，最长 128 字符，与后端校验一致，编辑时可以清除）；`frontend` 下 `npm test`（vitest）全部通过，修复已有失败项并在报告中列出。
- **追加（2026-11-21，A10 附带发现）前端评论幂等键**：
  - 问题：`pages/posts/[id].vue` 用 `ui-${Date.now()}`、`assistant-${Date.now()}`，`pages/hot-topics/[topic]/[post].vue` 用 `web-${postId}-${Date.now()}`，每次点击生成新键，网络重试不会复用，后端幂等对用户重试不起作用。
  - 要求：打开评论框时生成一次键（随机 UUID），发送失败重试时复用，发送成功后才换新键；后端已有幂等，无需改动。
  - 验收：补一个前端测试（vitest），证明同一次评论的重试使用同一个键，成功后下一条评论使用新键。
- **结果（`5e4bad4`）**：前端原本没有发帖和编辑页，新增 `/posts/new`、`/posts/edit/[id]`，话题校验与后端一致，编辑时留空保持、勾选清除发送 null（读接口不返回话题，无法预填）。三个评论框改用 `createCommentKey`。vitest RED 7 失败 → GREEN 17/17，原有测试无失败项；typecheck、build 通过。报告：`docs/perf-evidence/hardening-d-20261122/README.md`。

### D5 支付与退款仍为模拟实现
- 不接入真实支付。在 README 和相关设计文档里如实说明：支付和退款是模拟实现，哪些状态流转是模拟的，接入真实支付前需要补哪些东西（回调验签、对账、幂等）。
- **验收**：文档更新已提交；代码中没有任何地方暗示接入了真实支付。
- **结果（`3fa364e`）**：README 与 `docs/v1.1-material-commerce.md`（“支付与退款边界”）列出模拟的状态流转和接入前要补的事项；前端价格和付款提示标明“模拟”；Java/Python 中没有支付渠道相关代码。报告：`docs/perf-evidence/hardening-d-20261122/README.md`。


### D6 FFmpeg 容器创建前超时的残留
- **问题（D2 遗留）**：D2 在超时或中断时按名字 `docker rm -f`（`FfmpegTranscoder.java:94-97`）。如果超时发生在 docker CLI 还没把容器创建出来时，`rm -f` 找不到容器；之后 CLI 被杀但守护进程可能仍完成创建并运行，容器会残留。
- **建议修法**：定期清理任务，按标签 `io.github.xiiiiimu.content.ffmpeg=1`（`FfmpegTranscoder.java:18`）列出容器，删除创建时间超过运行时间上限（转码超时 + 余量）的容器。
- **验收**：测试构造一个带本项目标签、超过上限的容器，清理后不存在；未超过上限的容器和不带标签的容器不受影响。

---

## 第 E 轮：助手大模型解析器

### E1 @助手 接入大模型解析器
- **内容**：
  - `/v1/parse` 契约不变（含 A11 字段），只新增 `source`、`fallbackReason` 两个字段；Java 校验不变，仍是最后一道关。
  - 解析模式 `rules` / `llm` / `llm_with_fallback`，默认 `llm_with_fallback`：超时、调用失败、输出不合 schema / 空 / 截断时由规则版兜底，最多重试 1 次。
  - 供应商可配置：`CONTENT_ASSISTANT_LLM_PROVIDER` 取 `openai_compatible`（默认，DeepSeek `deepseek-flash`，JSON 模式）或 `anthropic`，模型和接口地址也可配置。key 只从 `CONTENT_ASSISTANT_LLM_API_KEY` 读取，不写入任何文件。
  - 缺 key 时：`llm_with_fallback` 告警后全部走规则版；`llm` 模式拒绝启动。
  - 隐私：模型只收到评论原文和最小上下文，不含任何 id 或用户名；日志不记录提示词和评论。
  - Java 解析超时 20 s → 30 s，可配置（`content.assistant.parse-timeout`），大于 Python 最坏 ≈21 s，小于 60 s 租约。
- **注入防护**：
  - 模型不能调用任何工具，评论放在分隔区内当作数据处理。
  - 新增必填输出字段 `droppedRequirements`：模型列出评论中没有被采纳的要求。只要非空就不执行：越权或试图改规则 → 拒绝，矛盾或含糊 → 补问。由 Python 确定性处理，绝不部分执行。
  - 另有关键词保险（`app/guard.py`），只能阻止执行，不会导致执行。
  - 修复前注入集 v1 的 inj03 在 3 次中有 2 次被部分执行；修复后 v1（第二次运行）和新冻结的 v2（`c6f37dd`，第一次运行）共 26 条，非法执行 0。
- **测试集结果**（84 行，只跑一次：大模型 ×3 + 规则版）：

  | 指标 | 规则版 | 大模型版 |
  |---|---|---|
  | 意图准确率 / 宏 F1 | 82.1% / 0.671 | 98.8% / 0.983 |
  | 执行判定准确率 | 60.7% | 97.6% |
  | 补问 P / R | 62.2% / 71.9% | 100% / 100% |
  | 抽奖全字段 | 0% | 100% |
  | E2E 非法执行 / 误搜索 | 0 / 20 | 0 / 0 |

  - 3 次运行的执行判定完全一致，降级比例 0%。
  - 延迟 P50 / P95 约 1.34 / 1.72 s。
  - 成本：实际约 $0.00013 / 次（缓存命中、非高峰）；缓存全部未命中时估算 $0.0027–0.0053 / 次。
- **验证**：pytest 151 项通过（无网络、无 key）；完整 `mvn -Pintegration verify` 通过（集成测试 455 项 0 失败；跳过的 93 项均为需显式开启的套件）。
- **提交**：`c6f37dd` 注入集、`68d2c31` Java 超时、`aa7b73a` 解析器、`9a87938` 评测工具、`572b779` 报告。
- **报告**：`docs/perf-evidence/assistant/llm-vs-rules-20261121.md`。dev 分数偏乐观，样例和标注由 AI 生成，以测试集为准。
- **遗留**：
  - Anthropic 供应商只用合成响应做过单元测试，未实测。
  - 真实调用中没有发生降级，降级比例和高峰时段的延迟、成本：缺证据。
  - 个别回复文案不准确（inj2-05、inj14、inj2-08），但都没有执行。
  - 生产注意：DeepSeek 的数据在中国境内处理，处理真实用户数据前需要评估合规。

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
| 2026-11-21 | E | E1 完成：@助手 接入大模型解析器（多供应商，默认 DeepSeek，`llm_with_fallback`），`droppedRequirements` 注入防护；测试集意图 98.8%（规则版 82.1%），执行判定 97.6%（60.7%），E2E 非法执行 0；两组注入集 26 条非法执行 0；Java 解析超时改为 30 s（`c6f37dd`…`572b779`） |
| 2026-11-22 | D | D2（`90452ce`）、D4（`5e4bad4`）、D5（`3fa364e`）、D3（`ba2cc30`，连续 10 次 0 失败）完成；D1（`2ec9483`）CI 拆为 4 个并行 job、真实 MQ 改用 Testcontainers、`setup-java` v5，本地验证通过，待推送后 CI；分批脚本修复旧 summary 误判并去掉过时的 AssistantEvalE2EIT 排除（`5d25a83`、`2ec9483`）；本地完整分批 IT 547 运行 0 失败 94 跳过。报告 `docs/perf-evidence/hardening-d-20261122/` |
| 2026-11-22 | D | 推送前补充：`GET /api/posts/{id}` 返回话题、编辑页预填（`0cf18d7`）；CI 新增 `frontend` job（`5d93a28`，pnpm frozen install + typecheck + vitest，15 min）；`CLAUDE.md` 更新（`d64729b`）。D1 仍待推送后 CI |
| 2026-11-22 | D | CI run `44941020312`（`5f88f18`）5 个 job 首次推送全部通过：frontend 26 s、assistant-python 16 s、maven (a-l) 7m44s、maven (m-z) 8m52s、maven-real-mq 3m07s（95/95）。D1 完成，第 D 轮全部完成 |
| 2026-11-22 | C | C1 盘点完成并写入 C 节（基于 `703ac60`）；C2 保留策略与拟实现的清理任务给出，C-1…C-14 待确认；新增 D6（D2 遗留：容器创建前超时的残留，未开始） |
| 2026-11-22 | C | C-1…C-14 按推荐决定；C2 清理任务实现（R-FEED、R-FOLLOW、R-SALE、R-NOTIFY、R-STREAM、TopicRetention 水位、V31 `completed_at`、broker 保留期校验），RED/GREEN 通过；C-6 计数记录；C3 立项（P1）并补 Redis 内存证据；完整分批 IT 被系统内存回收中断，待重跑。未提交 |
| 2026-11-22 | C | 完整验证改由 CI 承担；开发 Redis 设 `maxmemory 1gb`（C3 临时措施，四个服务 healthy、OOM 时写入被拒已验证）；按主题提交 `a8758cd`、`6966dc6`、`c536878`、`2c665b8` 并推送，待 CI |
