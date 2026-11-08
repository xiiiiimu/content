# M6 补验 + M7 可靠性 + M8 性能

基线：main `3c934d2`（PR #7 合并），main CI `42510086628` success。V2.1 + ADR-001～004。M9 不开始。本批一个 PR，不自动合并。

## 验收结转（PR #7）

| 项 | 结论 |
| --- | --- |
| M4 统一可见性 / 下架 / 缓存 | **通过** |
| M5 互动幂等 / stats / 热点重建 | **通过** |
| M6 S3 兼容上传、网关验签、租约 fencing | **部分通过**（S3Mock） |
| M6 官方 MinIO | **阻塞 / 待完成** |
| M6 真实 FFmpeg 产物 | **本批补验通过** |

## MinIO（再探测 2026-11-09）

仍不可用：Docker Hub denied、Quay 401。IT 继续 `adobe/s3mock`。**不得因替身关闭 MinIO 待完成项。** 已验证的 S3Mock 边界不回退。

## M6 补验

- 真实 Docker 镜像 `linuxserver/ffmpeg:version-7.1-cli` 执行转码
- **生产路径**：`FfmpegTranscoder.transcode(raw)` 对上传对象使用 `-i`；非媒体输入直接失败，**不会**用 lavfi 顶替
- lavfi 仅用于 `synthesizeFixture()` 生成带 `ftyp` 的测试夹具
- 断言：输出可被 ffprobe 解析、`meta_json` 含 format/streams、封面 JPEG、失败后换真实夹具重试恢复
- 证据：`MediaFfmpegIT`（本地 GREEN）；表字段 `cover_key`/`meta_json`（V8）

## M7 可靠性矩阵

| 场景 | 证据位置 | 本批 |
| --- | --- | --- |
| 全入口可见性回归（detail/feed/interact） | ReliabilityMatrixIT | 补齐 GREEN |
| Outbox/Inbox/Task 对账扫描 | PipelineReconcile.scan + ReliabilityMatrixIT | 补齐 |
| 对账修复幂等 / 与 Worker 并发 | PipelineReconcile.repair（INSERT IGNORE；不碰 RUNNING）+ ReliabilityMatrixIT | 补齐 GREEN |
| 优雅关停消费者 | PipelineConsumer.close（既有）+ DistributionIT AfterAll | 复用（生产路径未改） |
| 强制终止 Inbox/Worker/Relay/Recovery | DistributionIT / DeliveryWorkerIT / OutboxRelayIT / RecoveryAndModesIT | 复用 |
| 重复 / 乱序消息 | DistributionIT / RecoveryAndModesIT | 复用 |
| 租约接管 | DeliveryWorkerIT / MediaPipelineIT | 复用 |
| 派生视图恢复 | RecoveryAndModesIT / FeedRecovery | 复用 |
| 缓存回填竞态 + 权威失败 | RecoveryAndModesIT offline/authority barriers | 复用 |

## M8 性能（非默认 CI）

- **默认跳过**：`PerfHarnessIT` 带 `@EnabledIfSystemProperty(content.perf.enabled=true)`。未开开关时 JUnit 记为 **skipped**，**不得记为通过**。
- 脚本：`infra/perf/`；开关：`-Dcontent.perf.enabled=true`
- Ops：`feed_read` / `like_write` 稳态墙钟；`publish_visible` 仅种子；错误只计稳态抛错
- 负载：写:读=1:9；热点 like→top-10；客户端 8；JVM `-Xms512m -Xmx512m`；作者 20 / 帖 10 / 读者 40 / 关注 8（笔记本对照配额）
- 种子顺序：rebuild → 模式 → **先 follow** → drain → publish+accept+worker → 预热 → 稳态
- 原始结果与配置：`docs/perf-evidence/<mode>-<stamp>/`

### hybrid smoke 根因（旧 P95≈2214ms）

旧种子 **先 publish 再 follow**，PUSH Redis inbox 无收件人；权威校验大量空转拉高尾延迟。修复后 follow→publish→drain，hybrid P95≈711ms（同机对照）。

### 对照实验（修复种子后 / 同配额）

| 模式 | 轮次 | ops | thr/s | P95 | P99 | err% | pub→vis P95 | scanAmp P95 | writeAmp | backlog end | 证据 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| hybrid | pre-opt | 366 | 18.3 | 711 | 790 | 0 | 502 | 1.0 | 5.51 | 0 | `docs/perf-evidence/hybrid-20261109-043943` |
| push | pre-opt | 377 | 18.85 | 732 | 813 | 0 | 594 | 1.0 | 3.26 | 0 | `docs/perf-evidence/push-20261109-044435` |
| pull | pre-opt | 386 | 19.3 | 728 | 849 | 0 | 349 | 1.0 | 0 | 0 | `docs/perf-evidence/pull-20261109-044908` |
| hybrid | post-opt | 346 | 17.3 | 822 | 1408 | 0 | 515 | 1.0 | 5.51 | 0 | `docs/perf-evidence/hybrid-20261109-045554` |
| push | post-opt | 360 | 18.0 | 822 | 888 | 0 | 642 | 1.0 | 3.26 | 0 | `docs/perf-evidence/push-20261109-050142` |
| pull | post-opt | 394 | 19.7 | 735 | 854 | 0 | 305 | 1.0 | 0 | 0 | `docs/perf-evidence/pull-20261109-050637` |

预热：各模式 `warmupPublishVisibleSamples=200`；稳态错误率 0；积压 NEW/RUNNING 结束为 0。`clientSaturated=true` 表示闭环压测客户端打满，延迟由 SUT（Testcontainers MySQL + FeedMerger 串行 timed future）主导，而非驱动空转。

### 优化与复测结论

- **有效优化**：种子顺序（follow-first + accept/worker drain）——相对旧 smoke P95 2214→~711。
- **尝试**：Hikari 8→16、feedExecutor 8→16 / 队列 64。同配额复测未见稳定增益（噪声范围内），保留以承载并发 merge；未改 FeedMerger 语义（仍 AbortPolicy，避免 CallerRuns 嵌套死锁）。
- **未做 / 原因**：100 作者×50 帖生产级配额未跑（单次种子已数分钟级，笔记本时长不足）；官方 MinIO 仍阻塞；跨机绝对 SLA 不宣称。

## 未执行 / 仍阻塞

- 官方 MinIO 镜像健康（M6 阻塞，S3Mock 边界不变）
- 生产级长时压测 / 100×50×200 全量配额（本机时间与容器配额）
- M9 TrendGuard
