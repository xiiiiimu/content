# M6 补验 + M7 可靠性 + M8 性能

基线：main `3c934d2`（PR #7 合并），main CI `42510086628` success。V2.1 + ADR-001～004。M9 不开始。本批一个 PR，不自动合并。

## 验收结转（PR #7）

| 项 | 结论 |
| --- | --- |
| M4 统一可见性 / 下架 / 缓存 | **通过** |
| M5 互动幂等 / stats / 热点重建 | **通过** |
| M6 S3 兼容上传、网关验签、租约 fencing | **部分通过**（S3Mock） |
| M6 官方 MinIO | **阻塞 / 待完成** |
| M6 真实 FFmpeg 产物 | **本批补验** |

## MinIO（再探测 2026-11-09）

仍不可用：Docker Hub denied、Quay 401。IT 继续 `adobe/s3mock`。**不得因替身关闭 MinIO 待完成项。**

## M6 补验

- 真实 Docker 镜像 `linuxserver/ffmpeg:version-7.1-cli` 执行转码
- 断言：输出可被 ffprobe 解析、`meta_json` 含 format/streams、封面 JPEG、失败后重试恢复
- 证据：`MediaFfmpegIT`；表字段 `cover_key`/`meta_json`（V8）

## M7 可靠性矩阵

| 场景 | 证据位置 | 本批 |
| --- | --- | --- |
| 全入口可见性回归（detail/feed/interact） | ReliabilityMatrixIT | 补齐 |
| Outbox/Inbox/Task 对账 | PipelineReconcile + ReliabilityMatrixIT | 补齐 |
| 优雅关停消费者 | PipelineConsumer.close（既有）+ DistributionIT AfterAll | 复用 |
| 强制终止 Inbox/Worker/Relay/Recovery | DistributionIT / DeliveryWorkerIT / OutboxRelayIT / RecoveryAndModesIT | 复用 |
| 重复 / 乱序消息 | DistributionIT / RecoveryAndModesIT | 复用 |
| 租约接管 | DeliveryWorkerIT / MediaPipelineIT | 复用 |
| 派生视图恢复 | RecoveryAndModesIT / FeedRecovery | 复用 |
| 缓存回填竞态 + 权威失败 | RecoveryAndModesIT offline/authority barriers | 复用 |

## M8 性能（非 PR CI）

- 脚本：`infra/perf/`；开关：`-Dcontent.perf.enabled=true`
- 指标：吞吐、P50/P95/P99、错误率；证据目录 `target/perf-evidence/`
- 对比：push / pull / hybrid；热点 like 写入
- 不宣称跨机器可比的绝对 SLA；报告须附 commit 与配置快照

### 本地 smoke（Testcontainers 笔记本，非容量验收）

| 模式 | ops | thr/s | P95 ms | P99 ms | errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| hybrid | 105 | 7.0 | 2214 | 2683 | 0 |

证据：`target/perf-evidence/hybrid-smoke/summary.json`。完整 push/pull/hybrid 对比请用 `infra/perf/run-baseline.ps1` 重复运行；本 smoke 仅证明 harness 可产出原始日志与指标。

## 未执行 / 仍阻塞

- 官方 MinIO 镜像健康
- 生产级长时压测硬件台账外的容量规划
- M9 TrendGuard
