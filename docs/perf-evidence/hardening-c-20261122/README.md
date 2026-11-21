# 加固第 C 轮报告（C1–C3，P2 数据增长）

> 日期：2026-11-22 · 分支 `feat/v1.1-material-commerce` · 起点 `703ac60`（第 D 轮已推送，CI 两次通过）
> 原始日志、批次状态、RED 证据都在本地 `target/hardening-c/`（不提交）。本文只记录结论和数字。
> **状态：已推送，CI 结果见“CI 实际结果”。** 完整验证按决定交给 CI（本机完整分批 IT 被内存回收中断，见“测试”）。

## 提交

| 提交 | 项 | 内容 |
|---|---|---|
| `a8758cd` | C-5 | V31 `feed_delivery_tasks.completed_at`，置 DONE 时写入 |
| `6966dc6` | C2 | 清理任务（R-FEED、R-FOLLOW、R-SALE、R-NOTIFY、R-STREAM）、TopicRetention 水位、3 个 IT |
| `c536878` | C-14 | 回执保留期 ≥ broker 保留期 + 24 h 的启动校验；`broker.conf` 显式 `fileReservedTime=72` |
| `2c665b8` | C3 临时措施 | 开发 Redis `--maxmemory 1gb`，保留 `noeviction` |
| 本提交 | — | 计划与本报告 |

提交前扫描：变更中无明文密钥、令牌或大文件（命中项只有 `lease_token` 字段名和 Testcontainers 的 `getPassword()`）。第 2、3 个提交拆分时，中间状态单独 `test-compile` 通过；拆分后的最终文件与本地 GREEN 时一致。

## 改动

| 文件 | 项 | 内容 |
|---|---|---|
| `db/migration/V31__delivery_task_completed_at.sql` | C-5 | `feed_delivery_tasks.completed_at`（可空，INSTANT 加列） |
| `infrastructure/DeliveryMapper.java` | C-5 | 置 DONE 时写 `completed_at` |
| `infrastructure/RetentionMapper.java` | C2 | 全部清理 SQL、C-6 统计 SQL |
| `domain/RetentionBounds.java` | C2、C-14 | 保留期/批大小/每次批数；回执保留期 ≥ broker 保留期 + 24 h |
| `domain/FeedRetention.java` | R-FEED | outbox(SENT) → inbox → DONE 任务，同事务按事件删除 |
| `domain/FollowHistoryRetention.java` | R-FOLLOW | 只删失效 follow epoch |
| `domain/SaleRetention.java` | R-SALE | SENT dispatch + 消费回执（请求终态），同删同留 |
| `domain/NotificationRetention.java` | R-NOTIFY | 通知、风险事件 180 天；feedback log 在通知删除后 |
| `domain/SaleStreamRetention.java`、`infrastructure/SaleEntryRedis.java` | R-STREAM | 已确认 Stream 条目 + `t:`（排除隔离未处理）+ 孤儿 `stage:<gen>` |
| `domain/TopicRetention.java` | C-13（B1 预留） | ledger 只删已处理水位之前的行；默认 `ALL_PROCESSED` = 现行为 |
| `infrastructure/RetentionConfiguration.java` | C2 | 每个任务独立开关、批大小、每次批数、间隔、保留期；独立守护线程 |
| `infra/docker/broker.conf` | C-14 | 显式 `fileReservedTime=72` |
| `docs/hardening-plan.md` | — | C1 盘点、C2 决定与结果、C3 立项、D6 立项 |

保留策略、删除条件、锁影响、已知限制见 `docs/hardening-plan.md` 的 C1、C2 节。

## 测试

- **RED**（编译通过、容器正常、因缺少目标行为失败）：
  - `red-052247`：`FeedRetentionIT` 6/6 失败（删除数为 0、未写 `completed_at`、未校验 broker、未应用水位）。
  - `red-NotificationRetentionIT-052514`：1/1 失败。
  - `red-SaleRetentionIT-052716`：3/4 失败；`streamRetentionWithoutConsumerGroupTrimsNothing` 是守卫用例，空实现下即通过。
  - `red-052206`：测试代码编译错误（Lettuce 没有 `XAddArgs.Builder.id`），**不算 RED**，日志保留。
- **GREEN**：
  - `green-053218`：`FeedRetentionIT` 6/6 通过；`MySqlMigrationIT` 2 项失败，原因是迁移数断言仍为 30/29/28，这是新增 V31 的预期影响，更新为 31/30/29。
  - `green-053612`：`MySqlMigrationIT` 3/3、`NotificationRetentionIT` 1/1、`SaleRetentionIT` 4/4 通过。
- **完整分批 IT（`it-full-054219`）：未完成。** 完成 `b00-unit`（62 运行 / 0 失败 / 1 跳过）、`b01-AnonymousAccess`（44 / 0 / 0）后，在 `b02-AssistantEvalE2E` 期间被 Claude Code 以系统内存不足为由停止（会话空闲时的后台进程回收，不是测试失败）。Testcontainers 容器已由 Ryuk 回收，无残留进程。决定（2026-11-22）：不在本机重跑，完整验证交给 CI（覆盖全部常规测试和真实 MQ 测试，不受本机内存限制）。

## C-6 计数（本地开发库，只读；生产缺证据）

| 统计 | 数量 |
|---|---|
| `outbox_events` SENT 且 `sent_at` 为 NULL | 0 / 39,912 |
| `material_sale_dispatch` SENT 且 `sent_at_ms` 为 NULL | 0 / 70,702 |
| 没有 dispatch 行的消费回执（V20 之前，R-SALE 不处理） | 10,210 / 80,912 |

保留；以后可做一次性人工清理。

## C3 证据：抢购 Redis 状态哈希

本地开发 Redis（往期压测数据，80,912 个请求），2026-11-22 只读测量：

| 指标 | 值 |
|---|---|
| 字段数 / `MEMORY USAGE`（SAMPLES 0） | 333,459 / 62,643,215 B（hashtable） |
| 每字段均摊开销（总量 − 原始字节）/ 字段数 | 约 54.5 B |
| 每请求（`r:`+`k:`+`o:`，恢复后形态） | 约 591 B |
| 每请求（另含结算后的 `v:`） | 约 653 B |
| 每 1 GiB 可容纳请求数 | 约 164 万（653 B）/ 182 万（591 B） |
| maxmemory / 策略 | 测量时**未设置（0）** / `noeviction`，`appendonly yes`；之后开发配置改为 `1gb`（临时措施，见下） |
| Docker Desktop VM 内存 | 4.80 GiB（与 MySQL、RocketMQ、MinIO 共用） |

方法：Lua `HSCAN` 按前缀统计字段数和字节数，用 `MEMORY USAGE` 总量反推每字段开销；是平均估算。风险和方向见计划 C3。

**临时措施**：开发 compose 的 Redis 设 `--maxmemory 1gb`、保留 `noeviction`。验证：四个服务 healthy；上限临时调到 1mb 时写入脚本被拒（`OOM command not allowed`），入口按代码映射为 503 `SALE_CACHE_UNAVAILABLE`。根治方向未实现。

## 遗留

- 完整分批 IT 需要重跑。
- C3（P1，未开始）、D6（P3，未开始）。
- R-FEED/R-SALE 的长期不可删行会占用每次运行的批次；R-NOTIFY 遇到第一条未过期通知就停（见计划 C2“已知限制”）。
