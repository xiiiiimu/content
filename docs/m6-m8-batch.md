# M6 补验 + M7 可靠性 + M8 性能

基线：main `3c934d2`（PR #7 合并）。V2.1 + ADR-001～004。M9 不开始。本批一个 PR，不自动合并。

## 验收结转（PR #7）

| 项 | 结论 |
| --- | --- |
| M4 统一可见性 / 下架 / 缓存 | **通过** |
| M5 互动幂等 / stats / 热点重建 | **通过** |
| M6 S3 兼容上传、网关验签、租约 fencing | **通过**（S3Mock IT 仍保留） |
| M6 官方 MinIO | **通过**（源码构建 `content-minio` + `MediaMinioIT`） |
| M6 真实 FFmpeg 产物 | **通过**（审查修复后重验） |

## MinIO

官方镜像 Hub/Quay 不可拉取；本批从 `github.com/minio/minio` `RELEASE.2025-04-22T22-12-26Z` 源码构建 `content-minio`（见 `infra/docker/minio/SOURCE.md`）。`HttpS3ObjectStore` 使用 AWS SigV4 path-style。S3Mock IT 不回退、不替代 MinIO 验收。证据：`docs/minio-evidence/ACCEPTANCE.md`。本地开关：`-Dcontent.minio.it=true`（默认 CI skip ≠ 通过）。

## M6 补验

- 生产路径读取实际上传对象（`-i`）；lavfi 仅夹具
- 输出可解码（`verifyDecodable`）、metadata format/streams、封面 JPEG
- 失败重试恢复；下架竞争 → `POST_GONE_OR_VERSION`，不得 DONE
- 证据：`MediaFfmpegIT`（3 tests GREEN）

## M7 对账

**冻结检测范围：** SENT 无 inbox；PUSH/HISTORY 无 task；OFFLINE 仍占 stats；orphanTasks 只检测不改写。

**修复规则：** `DistributionInbox.accept` + `createTaskIgnore`；不碰 RUNNING；repair 开始冻结 gap id；并发新 SENT 留到下一轮。

证据：`ReliabilityMatrixIT`（中途失败恢复、重复执行、Worker 并发、并发写入下次冻结可见）GREEN。

## M8 性能

- 默认 skipped ≠ 通过
- 计时前断言模式覆盖 + 结果集 = 种子语料；`valid:true` + `mode-coverage.json`
- **旧结果全部作废**：见 `docs/perf-evidence/VOIDED.md`（保留原始文件与原因）

### 有效对照（修复后，配额 authors=20 posts=10 readers=40 follows=8 duration=20s）

| 模式 | ops | thr/s | P95 | P99 | err% | pub→vis P95 | coverage | 证据 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| hybrid | 332 | 16.6 | 792 | 1037 | 0 | 560 | inbox=100 pull=100 corpus=200/200 | `docs/perf-evidence/hybrid-20261109-054301` |
| push | 341 | 17.05 | 828 | 1244 | 0 | 670 | inbox=200 pushAuth=200 | `docs/perf-evidence/push-20261109-060107` |
| pull | 356 | 17.8 | 801 | 1181 | 0 | 284 | pullCand=200 PUSH_TASK=0 | `docs/perf-evidence/pull-20261109-060546` |

同条件附加跑次（亦 `valid:true`，噪声较大，不作主对照）：`hybrid-20261109-055031`、`push-20261109-054733`。

## 里程碑（合并后）

- **M6**：已有真实 MinIO 本地验收（源码构建 pin + SigV4 + `MediaMinioIT` + recreate 哈希保留）。部分故障用例（处理中下架竞争、fail/retry）仅在 S3Mock 验证。
- **M7**：按现有矩阵验收（`ReliabilityMatrixIT` 含对账中断恢复）。
- **M8**：小规模对照完成（三模式 `valid:true`）；完整容量验收待办。
- **M9**：不自动开始。

## 未执行 / 仍阻塞

- 100×50×200 全量配额（本机时长）
- M9 TrendGuard
- CI 内构建/跑官方 MinIO（镜像需本机构建；`MediaMinioIT` 默认 skip）
