# M0–M9 验收汇总

基线：V2.1 §14 里程碑 + ADR-001～005。  
当前 main：`0872acc`（PR #10 merge；M9 tip `606b1d2`）。  
main CI：[42726581884](https://github.com/xiiiiimu/content/actions/runs/42726581884) success。

**本汇总不追加 M8 长跑，不扩展新功能。**

---

## 已完成项

| 里程碑 | 完成内容（摘要） | 主要证据 |
| --- | --- | --- |
| **M0** | Java 17 / Spring Boot 3 / MyBatis 骨架；Compose MySQL/Redis/RocketMQ；Flyway 空库迁移 | 启动与迁移 IT；`docs/m0-m3-implementation-plan.md` |
| **M1** | 幂等发布、作者模式行锁、Post+Outbox 同事务；Relay 租约 / ACK→SENT | `PublishPostIT`；`OutboxRelayIT`；`docs/m1-story*.md` |
| **M2** | Redis 有序候选、混合读取、签名游标/身份、权威谓词与有界续读 | `RedisFeedCandidateStoreIT`；`ProductionFeedIT`；`FeedMergeIT` |
| **M3** | Inbox+Task、分发租约、关注生命周期、模式迁移、受控 Redis 恢复 | `DistributionIT`；`DeliveryWorkerIT`；`RecoveryAndModesIT` |
| **M4** | 统一可见性 / 下架 / 缓存不可授权 | `VisibilityAndOfflineIT`；ADR-004 |
| **M5** | 互动幂等、stats 对账、热点 ledger/分钟桶/滚动榜/重建 | `InteractionsAndTrendingIT`；`/api/topics/trending` |
| **M6** | 媒体上传、任务租约 fencing、网关验签；本地 MinIO + 真实 FFmpeg 产物补验 | `MediaPipelineIT`；`MediaMinioIT`（默认 skip≠通过）；`MediaFfmpegIT` |
| **M7** | 对账检测与受限修复（不碰 RUNNING）；可靠性矩阵 | `ReliabilityMatrixIT` |
| **M8** | 小规模三模式 perf 对照（`valid:true`）；容量长跑 **QUEUE-PAUSED / 不追加** | `docs/m8-capacity-plan.md`；`docs/perf-evidence/*`；PR #9 |
| **M9** | 风险事件契约 + 生产 emit + ops 只读查询 + 独立 IT（ADR-005） | `TrendGuardRiskIT`；`docs/m9-trendguard.md`；PR #10 |

结转（非独立里程碑重定义）：

| 结转 | 说明 |
| --- | --- |
| §8 / ADR-004 **T10b** 话题帖列表 | `GET /api/topics/{topic}/posts` 经 `VisibilityService`；随 PR #10 落地，**不计**作 M9 §14 替代 |

---

## 保留边界（明确未做 / 不做）

| 边界 | 状态 |
| --- | --- |
| M8 **完整容量**长跑 / 最高容量宣称 | **不追加**（`QUEUE-PAUSED`） |
| TrendGuard 亚分钟窗口 / ML / Herd 源码或 Collector 兼容 | **非目标**（ADR-005） |
| 独立 Trend 微服务 / ELK 源码兼容 | **非目标** |
| Redis Cluster / 无停机追平写流量 | **未宣称**（M2/M3 边界） |
| CI 内官方 MinIO 全量构建跑通 | `MediaMinioIT` 默认 skip；本地源码镜像验收另记 |
| 编造性能数字 | **禁止**（§15） |

---

## M9 对照 §14（关闭条件）

1. **风险事件契约** — ADR-005；确定性 `eventId`；业务 UNIQUE；MySQL 权威。  
2. **只读查询接口** — `GET /api/trendguard/risks`；`TRENDGUARD_OPS` 专用。  
3. **独立集成验收** — `TrendGuardRiskIT`；硬化 RED→GREEN 保留于历史。  

→ **已关闭。** 详见 `docs/m9-trendguard.md`、`docs/adr/0005-trendguard-risk-events.md`。
