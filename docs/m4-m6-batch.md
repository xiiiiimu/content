# M4–M6 批量实施记录

基线：main `e4c6b50`（PR #6 合并），main CI run `42494438305` success。V2.1 与 ADR-001～004；本批覆盖 M4 统一可见性、M5 互动/热点、M6 媒体。PR #7；合并前审查修补鉴权缺口。

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

**阻塞（待完成，不因 S3Mock 关闭）**：无法取得可维护的官方 MinIO 镜像或二进制。  
**可运行替身（仅开发/IT）**：`adobe/s3mock:3.12.0`；`linuxserver/ffmpeg:version-7.1-cli` 可拉取。客户端只经 HMAC 网关 `/api/media/objects`，不把替身称作 MinIO。

## 冻结语义（不改）

- 可见性以 MySQL 权威状态与关注权限为准；缓存不能授权
- 下架条件更新带 version；旧媒体任务不得复活
- 点赞关系唯一键幂等；stats 可对账且需鉴权
- 热点事件 ledger 去重；净增量 ≠ 有效点赞数
- 媒体私有对象；短期签名（≤15m）；签发前查权威状态；网关验签

## 本批验收矩阵

| 能力 | 结论 | 证据 |
| --- | --- | --- |
| M4 统一可见性 / 下架 / 缓存一致性 | **通过** | VisibilityAndOfflineIT；AuthoritativeVisibility（作者或关注谓词）；CachedPostDetails |
| M5 点赞/取消点赞幂等 | **通过** | InteractionsAndTrendingIT；`post_likes` PK |
| M5 评论幂等 | **通过** | `uq_comment_idem`；replay=true |
| M5 计数对账 | **通过** | `reconcile` → `post_stats`；stats 需 Bearer |
| M5 热点 ledger / 分钟桶 / 滚动榜 / 重建 | **通过** | ledger + buckets；`rebuild`；`/api/topics/trending` |
| M5 下架后互动拒绝 / 非关注者拒绝 | **通过** | OFFLINE 与 non-follower `visibility.read` 空 |
| M6 上传 + 异步任务 + 网关验签 + 租约 fencing | **部分通过** | MediaPipelineIT（S3Mock）；HMAC gateway；旧 token `complete`=0 |
| M6 官方 MinIO | **阻塞 / 待完成** | 社区镜像不可用 |
| M6 真实 FFmpeg 产物（可解码/元数据/封面/失败恢复） | **未执行 / 待完成** | 当前 `transcode` 为占位派生；补验在后续批次 |

不宣称：MinIO 官方健康、真实 FFmpeg 产物、热点性能 SLA、Cluster Redis、T10b 完整列表权限过滤。

## 本地 / CI

- 全量 `mvn -Pintegration verify` 与 PR tip CI 以合并前最新运行为准
- PR：https://github.com/xiiiiimu/content/pull/7
