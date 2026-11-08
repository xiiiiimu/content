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
