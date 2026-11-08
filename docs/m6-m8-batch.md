# M6 补验 + M7 可靠性 + M8 性能

基线：main `3c934d2`（PR #7 合并）。V2.1 + ADR-001～004。M9 不开始。本批一个 PR，不自动合并。

## 验收结转（PR #7）

| 项 | 结论 |
| --- | --- |
| M4 统一可见性 / 下架 / 缓存 | **通过** |
| M5 互动幂等 / stats / 热点重建 | **通过** |
| M6 S3 兼容上传、网关验签、租约 fencing | **部分通过**（S3Mock） |
| M6 官方 MinIO | **阻塞 / 待完成** |
| M6 真实 FFmpeg 产物 | **通过**（审查修复后重验） |

## MinIO

仍阻塞。IT 继续 `adobe/s3mock`。**不得因替身关闭 MinIO 待完成项。** S3Mock 边界不回退。

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

## 未执行 / 仍阻塞

- 官方 MinIO
- 100×50×200 全量配额（本机时长）
- M9 TrendGuard
