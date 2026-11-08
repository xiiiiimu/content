# M4–M6 批量实施记录

基线：main `e4c6b50`（PR #6 合并），main CI run `42494438305` success。V2.1 与 ADR-001～004；本批覆盖 M4 统一可见性、M5 互动/热点、M6 媒体。最终一个 PR，不自动合并。

## M2/M3 验收结转（本批前置）

| 能力 | 结论 | 证据 |
| --- | --- | --- |
| 生产混合 Feed | 通过 | ProductionFeedIT / FollowingFeed 主库+READY Redis+PUSH 兜底 |
| 签名游标 / HTTP | 通过 | FeedTokens + FeedIdentityFilter + ProductionFeedIT |
| 关注生命周期 | 通过 | RelationshipService + ProductionFeedIT / RecoveryAndModesIT |
| 可靠分发 | 通过 | DistributionIT / DeliveryWorkerIT 真实 MQ/租约/终止 |
| 模式迁移 | 通过 | FeedModes + RecoveryAndModesIT |
| Redis 恢复 | 通过 | FeedRecovery + RecoveryAndModesIT |
| T01–T09 / T13–T18 | 本地+main CI 通过 | docs/m2-m3-batch.md；CI 42494438305 |
| T10a–c | 本批（M4–M6） | ADR-004 |

无未解释遗漏；M2/M3 不宣称性能收益/无停机追平/Cluster。

## MinIO 探测（2026-11-08）

| 探测 | 结果 |
| --- | --- |
| `minio/minio:*` Docker Hub | pull access denied |
| `quay.io/minio/minio:*` | 401 UNAUTHORIZED |
| `bitnami/minio:*` | not found |
| `dl.min.io` 官方二进制 | **410 Gone**（社区 MinIO Server 已归档停服） |
| 证据 | `target/m4-m6-batch/minio-image-probe.log`、`minio-binary-probe.log` |

**阻塞**：无法取得可维护的官方 MinIO 镜像或二进制（社区 MinIO 已归档）。  
**可运行替身（仅开发/IT）**：`adobe/s3mock:3.12.0`（S3 API）与 `linuxserver/ffmpeg:version-7.1-cli` 可拉取。M6 按 S3 兼容接口验收；不把替身称作 MinIO，不伪造官方镜像健康结果。

## 内部实施顺序

1. M4 VisibilityService + 详情入口 + 缓存与下架一致性（T10a）
2. M5 点赞/评论幂等与 post_stats 对账；热点 ledger/分钟桶/滚动榜重建（T10b 列表过滤子集）
3. M6 MediaTask 租约、FFmpeg 异步、媒体鉴权；对象存储对接受 MinIO 阻塞约束
4. 全量 `mvn -Pintegration verify`、推送、开 PR、等 CI

## 冻结语义（不改）

- 可见性以 MySQL 权威状态为准；缓存不能授权
- 下架条件更新带 version；旧媒体任务不得复活
- 点赞关系唯一键幂等；stats 可对账
- 热点事件 ledger 去重；净增量 ≠ 有效点赞数
- 媒体私有对象；短期签名；签发前查权威状态

## 本批验收矩阵

| 能力 | 结论 | 证据 |
| --- | --- | --- |
| M4 统一可见性 / 下架 / 缓存一致性 | 通过 | VisibilityAndOfflineIT；AuthoritativeVisibility；CachedPostDetails |
| M5 点赞/取消点赞幂等 | 通过 | InteractionsAndTrendingIT；`post_likes` PK；重复 like 计数不变 |
| M5 评论幂等 | 通过 | `uq_comment_idem(user_id,idempotency_key)`；replay=true |
| M5 计数对账 | 通过 | `InteractionService.reconcile` → `post_stats` |
| M5 热点 ledger / 分钟桶 / 滚动榜 / 重建 | 通过 | `topic_event_ledger` + `topic_minute_buckets`；`TrendingService.rebuild`；HTTP `/api/topics/trending` |
| M5 下架后互动拒绝 | 通过 | OFFLINE 后 `visibility.read` 空；like 抛 `FeedFailure` |
| M6 上传 + 异步媒体任务 | 通过 | MediaPipelineIT；`media_tasks` NEW→RUNNING→DONE；S3 put/get |
| M6 租约接管 | 通过 | MediaPipelineIT 强制过期 lease 后 tick 可接管 |
| M6 媒体鉴权 / 下架阻断签名 | 通过 | sign 前查 VisibilityService；offline 后 sign 失败 |
| M6 官方 MinIO | **阻塞** | 社区镜像/二进制不可用；IT 用 `adobe/s3mock`；FFmpeg 主机缺失时用派生占位 + 镜像名记录 |

不宣称：真实 FFmpeg 容器内转码产物校验、MinIO 官方发行版健康、热点性能 SLA、Cluster Redis。

## 本地验证摘录

- `InteractionsAndTrendingIT`：通过（MySQL 8.4 + Redis 7.4）
- `MediaPipelineIT`：通过（MySQL + Redis + adobe/s3mock:3.12.0）
- `VisibilityAndOfflineIT` / `MySqlMigrationIT`（V1→V7）：通过
- 全量 `mvn -Pintegration verify`：**124 tests, Failures: 0, Errors: 0, BUILD SUCCESS**（`target/m4-m6-batch/full-verify-002442.log`）
- PR：https://github.com/xiiiiimu/content/pull/7 （不自动合并）
