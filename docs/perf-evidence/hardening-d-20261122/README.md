# 加固第 D 轮报告（D1–D5，P3 测试缺口）

> 日期：2026-11-21 → 11-22 · 分支 `feat/v1.1-material-commerce` · 起点 `d90097e`
> 原始日志、批次状态、RED 证据都在本地 `target/hardening-d/`（不提交）。本文只记录结论和数字。
> **CI 状态：尚未推送，没有 CI 结果。** 推送后补记 run 号和每个 job 的耗时。

## 提交

| 提交 | 项 | 内容 |
|---|---|---|
| `5d25a83` | — | 分批脚本：每批前把旧的 `failsafe-summary.xml` 移到证据目录（不删除） |
| `90452ce` | D2 | FFmpeg 超时或中断后删除容器 |
| `5e4bad4` | D4 | 发帖/编辑页话题输入框；评论幂等键改为打开评论框时生成、重试复用 |
| `3fa364e` | D5 | 文档说明支付、退款为模拟实现；前端价格和付款提示标明“模拟” |
| `ba2cc30` | D3 | 关闭由测试自己驱动 `tick()` 的 IT 里的后台媒体扫描 |
| `2ec9483` | D1 | CI 拆成并行 job（每个 35 分钟上限）；真实 MQ 套件改用 Testcontainers；`setup-java` v5 |
| `e1f46c0` | — | 本报告初稿与计划状态 |
| `0cf18d7` | 补充 | `GET /api/posts/{id}` 返回话题；编辑页预填当前话题 |
| `5d93a28` | 补充（D1） | CI 新增 `frontend` job：依赖安装、typecheck、vitest，15 分钟 |
| `d64729b` | 补充 | `CLAUDE.md`：FFmpeg 清理已验证，补充 MQ / MinIO / FFmpeg 测试的运行方式 |

## D2 FFmpeg 超时后的容器清理

- **问题确认（RED）**：新增 `FfmpegTranscoderTimeoutIT.timeoutStopsAndRemovesTheContainer`：用 `-re` 让 ffmpeg 按实时速度跑 600 s，超时设 20 s。修复前超时后 10 s 内容器 `85d4fc67…` 仍在运行（`target/hardening-d/d2-red-234313`）。原因：超时只杀掉本地 docker CLI，`--rm` 只在容器退出后删除。RED 合格：编译通过、环境健康、因缺少清理而失败。
- **修复（GREEN）**：每次运行都带唯一 `--name content-ffmpeg-<uuid>` 和标签 `io.github.xiiiiimu.content.ffmpeg=1`；超时或线程中断时执行 `docker rm -f <name>`（30 s 超时，失败作为 suppressed 附在原异常上，中断标志会恢复）。默认超时仍为 2 分钟，没有调整。
- **测试**：超时、中断两个用例都通过，没有残留容器。中断用例与修复一起写，**没有单独的 RED**。
- **遗留**：如果超时发生在 docker CLI 刚提交创建请求、容器还没出现时，`rm -f` 可能早于容器创建（理论窗口，未复现）。

## D3 `offlineDuringProcessingFailsLeaseWithoutCompleting` 偶发失败

- **根因**：测试夹具的竞态，不是生产竞态。`MediaFfmpegIT` 没有关闭 `MediaScanConfiguration` 的 1 s 定时扫描。扫描线程可能在 `attach()` 和测试自己的 `tick()` 之间认领任务：钩子要么在扫描线程上执行，要么完全不执行，测试随之失败。其他 media IT 早已设置 `content.media.scan-delay-millis=3600000`。生产代码的租约令牌和版本检查在所有路径上都正确。
- **证据（RED）**：新增夹具守卫 `backgroundScannerDoesNotClaimTasksDrivenByTheTest`（附件后等 3 s，任务必须仍是 `NEW`）。修复前任务已是 `DONE`（`target/hardening-d/d3-red-234900`）。
- **修复**：`MediaFfmpegIT` 设置 `scan-delay-millis=3600000`；`MediaPipelineIT` 有相同模式（手动 `tick()`、扫描开着），同样加上，属预防性修改，该类此前没有观察到失败。
- **验证**：修复后**连续运行 10 次**，`MediaFfmpegIT` 每次 4/4 通过，0 失败（第 1 次同时跑了 `MediaPipelineIT` 2/2），见 `target/hardening-d/d3-green-repeat-235134`。**10 次只能提供有限的置信度**：能说明原来的失败路径已被消除，不足以排除低概率的其他偶发问题。修复前的失败率没有用重复运行测量，历史上只有 1 次失败记录，日志没有保留（缺证据），根因由上面的 RED 守卫确定性复现。

## D4 前端话题输入与评论幂等键

- **话题输入**：前端此前没有任何发帖或编辑页面，本轮新增：
  - `/posts/new`：正文 + 可选话题 → `POST /api/posts` → 发布。发布失败时保留已建草稿，重试只再次发布（发布用同一个幂等键）。
  - `/posts/edit/[id]`：初版时 `GET /api/posts/{id}` 不返回话题，话题框留空表示保持、勾选“清除话题”发送 `topic: null`。推送前的补充（见下文“推送前补充”）改为预填当前话题，去掉了勾选框。
  - 校验与 `PostAuthoringService.normalizeTopic` 一致：去首尾空白、空白即无话题、最长 128、不含控制字符。
  - 导航加“发帖”，帖子页作者可见“编辑正文 / 话题”入口。
- **评论幂等键**：三个评论框（帖子页的时间点反馈、@助手评论、热点帖评论）改用 `createCommentKey`：打开评论框时生成一次随机 UUID，失败重试复用，成功后才换新键；切换到另一个帖子时重置（后端的键按用户唯一，不按帖子）。
  - 已知取舍：后端只按键重放。如果首次发送其实已成功但响应丢失，用户改了文字再重试，会拿到原评论。
- **测试**：vitest RED 7 项失败（缺少行为，`target/hardening-d/d4-red-vitest-*.log`），GREEN 17/17。原有 8 项没有失败项，无需修复。`nuxt typecheck`、`nuxt build` 通过。没有做浏览器端手工验证。

## D5 支付与退款为模拟实现

- README 新增“Payments and refunds are simulated”一节；`docs/v1.1-material-commerce.md` 新增“支付与退款边界（模拟实现）”，逐项列出模拟的状态流转（落单直接写 `PAID`，各类退款直接 `PAID → REFUNDED`，不发生资金流动），以及接入真实支付前要补的回调验签、幂等、状态机、对账等；`material-commerce-addendum.md` 加了指向说明。
- 代码检查：Java、Python 中没有支付渠道、回调、网关相关代码。前端价格和付款结果提示改为标明“（模拟）”，独立购买页加了一行“不产生真实扣款或退款”。

## D1 CI 覆盖

### 新的 workflow（`.github/workflows/ci.yml`）

| job | 内容 | 超时 |
|---|---|---|
| `frontend` | `pnpm install --frozen-lockfile` + `pnpm run typecheck` + `pnpm run test`（vitest） | 15 min（推送前补充） |
| `assistant-python` | `pip install` + `pytest` | 10 min（未变） |
| `maven (a-l)` | 单元测试 + 类名首字母 A–L 的 IT | 35 min |
| `maven (m-z)` | 类名首字母 M–Z 的 IT（不跑单元测试） | 35 min |
| `maven-real-mq` | `CONTENT_REAL_MQ_IT=true`，`CommerceRealMqIT` + `CommerceDispatchBatchRealMqIT` | 35 min |

- **真实 MQ 改用 Testcontainers**：这两个套件原来连本地 compose 的 broker（`127.0.0.1:29876`，`docker exec content-broker-1` 建 topic），在 runner 上无法运行。新增 `CommerceMqFixture`（RocketMQ 5.3.2，namesrv + broker，broker 固定端口 30924，与已有的 30921/30922/30923 不冲突），每个 JVM 只启动一次，每个套件建自己的 topic。
- **FFmpeg**：`FfmpegTranscoder` 只通过 Docker 镜像运行 ffmpeg，从不调用宿主机 ffmpeg，所以**在 runner 上安装 FFmpeg 没有用处，没有安装**。FFmpeg 相关测试此前已经在 CI 运行（CI artifact 中 `MediaFfmpegIT` 3/3、`FfmpegTranscoderExecTest.waitForDestroysHungUnixProcess` 已执行）。新的 workflow 在 maven job 开头预拉镜像，避免拉取时间落进测试自己的超时。
- **拆分方式**：按 IT 类名首字母切分，新增的 IT 类一定会落进其中一个 job，不需要维护类名清单。本地确认了 failsafe 3.5.6 对这种前缀通配的匹配（`Pu*IT,Po*IT` 恰好选中 `PublishPostIT`、`PostTopicIT`，且没有跑单元测试）。
- `actions/setup-java` v4 → v5。
- actionlint 1.7.7（官方 Docker 镜像）：0 错误。

### 本地按 job 实测（分批）

| job | 本地方式 | 结果 | 耗时 |
|---|---|---|---|
| `assistant-python` | 新建 venv，`pip install -r requirements.txt` 后 `pytest -q tests` | 151 passed | pytest 11 s；pip 107 s（本地无缓存） |
| `maven-real-mq` | 两个类放同一批（同一 JVM，与 CI 相同） | 95/95，0 失败 | 231 s（含 Maven 启动编译） |
| 同上，分开两批 | 每批 1 个类 | 54/54、41/41 | 140 s、128 s |
| `maven` 两个分片合计 | 分批脚本完整跑（28 批，每批 ≤2 个 IT 类） | 547 运行，0 失败，0 错误，94 跳过 | Maven 时间合计 39.7 min（墙钟 47.3 min） |
| 其中 A–L | 按批次耗时分摊 | — | ≈19.0 min |
| 其中 M–Z | 同上 | — | ≈19.9 min |
| 单元测试 | b00 批 | 通过（1 跳过，见下） | 46–47 s |
| `frontend` | 已跟踪文件复制到干净目录后按 job 命令运行 | typecheck 通过，vitest 21/21 | 安装 21 s（本机 pnpm 缓存已热）、typecheck 12 s、vitest 3 s |

- **关于 35 分钟**：分批合计 39.7 min 包含 28 次 Maven 启动、编译和每批重复的 `ContentApplicationTest`，比单次调用偏高；CI 实际是一个 job 一次调用。按这个偏保守的口径，拆分后每个分片约 19–20 min，加上 runner 上的拉镜像和依赖下载，仍低于 35 min。**每个 job 在 CI 上的真实耗时：缺证据，等推送后的 CI 运行。**
- 分批运行时，完整 IT 在 D1 的 Java 改动之后、`ci.yml` 拆分之前完成；之后只改了 `ci.yml`，没有 Java 改动。

### CI 中仍然跳过的测试

| 测试 | 跳过数 | 原因 |
|---|---|---|
| `MediaMinioIT` | 1 | 需要 `-Dcontent.minio.it=true`；没有可维护的官方 MinIO 镜像或二进制（`docs/m4-m6-batch.md:30`），限制照旧 |
| `PerfHarnessIT` | 1 | 需要 `-Dcontent.perf.enabled=true`；性能实验，不进 CI |
| `AssistantEvalE2EIT` | 类级跳过（报告为 0 运行） | 评测工具，需要 `-Dassistant.eval.cases`/`-Dassistant.eval.out`，不是回归测试；仍会启动 Spring 上下文（约 20 s） |
| `FfmpegTranscoderExecTest.waitForDestroysHungWindowsProcess` | 1 | `@EnabledOnOs(WINDOWS)`，Linux runner 上跳过；对应的 Unix 用例在 CI 运行 |
| `CommerceRealMqIT`、`CommerceDispatchBatchRealMqIT`（在两个 maven 分片中） | 39 + 52 | 没有 `CONTENT_REAL_MQ_IT`，属预期；它们在 `maven-real-mq` job 里全部运行（启用时为 41 + 54 个用例） |

- 计划里原先写 `FfmpegTranscoderExecTest` “跳过原因待核实”：实际是按操作系统跳过，不依赖 docker 或 ffmpeg 镜像。
- `AssistantEvalE2EIT` 在 `c664f49` 提交后，分批脚本仍把它当作“未提交的工作”排除；本轮去掉了这条过时的排除，现在本地完整跑与 CI 的范围一致。

## 推送前补充（用户 2026-11-22 决定）

- **读接口返回话题**（`0cf18d7`）：话题是公开信息（话题页已公开列出），所以 `GET /api/posts/{id}` 对所有能看到帖子的读者返回 `topic`，没有话题时为 null；看不到帖子的人仍是 404。`/api/topics/{topic}/posts` 用同一个 `View`，也随之多了这个字段（只增字段）。Redis 详情缓存只用于版本号，没有改。
  - RED：`PostTopicIT.postDetailReturnsTheTopicToEveryReaderWhoCanSeeThePost` 失败于 `No value at JSON path "$.topic"`（`target/hardening-d/d6-topic-red-030656`）。
  - GREEN：`PostTopicIT` 和所有用到 `View` 或这些接口的 IT 共 13 个类分 7 批运行，全部通过，115 运行、0 失败（`target/hardening-d/d6-topic-green-031121`）。
- **编辑页预填**：话题框填入当前话题。不改就不发送 `topic`（保持），改了发送新值，清空发送 null（清除）；“清除话题”勾选框去掉。新增 `editPayloadFromLoaded` 和 `tests/topic-prefill.spec.ts`：RED 3 项失败（`target/hardening-d/d6-fe-red-031026`），GREEN 21/21。
- **前端 CI job**（`5d93a28`）：要求的是 `npm ci`，但前端是 pnpm 项目（`packageManager: pnpm@9.15.0`，只有 `pnpm-lock.yaml`，没有 `package-lock.json`），`npm ci` 会直接失败。所以用等价的 `pnpm install --frozen-lockfile`，同样严格按锁文件安装。本地在干净目录跑通（`target/hardening-d/d6-fe-ci-local-031415`）。第一次在 `C:\WINDOWS\TEMP` 下跑时 typecheck 因路径大小写（TS1149）失败，属本机环境问题，日志保留在 `d6-fe-ci-local-031215`。actionlint 0 错误。
- **`CLAUDE.md`**（`d64729b`）：已知边界改为 FFmpeg 清理已在 D2 验证（附遗留窗口）；明确集成测试用 Testcontainers、不依赖 compose；新增“测试运行方式”：真实 MQ 需 `CONTENT_REAL_MQ_IT=true` 并由 Testcontainers 启动，FFmpeg 走 Docker 镜像，MinIO 和 Perf/Eval IT 的开关，CI 超时规则。

## 其他发现

- 分批脚本：只跑单元测试的批次（b00）不会重写 `failsafe-summary.xml`，`verify` 会读到上一次运行留下的失败结果。D2 GREEN 第一次尝试因此失败（`target/hardening-d/d2-green-attempt1-stale-summary-234541`，是环境问题，不是 RED），已修复（`5d25a83`）。
- 本机默认 Python 没有 pytest，第一次 pytest 因此失败（日志保留），改用干净的 venv 后通过。

## 遗留问题

1. 推送后的 CI 结果，以及每个 job 的实际耗时（缺证据）。
2. D2：容器尚未创建时就超时的理论窗口（见上）。
3. D3：只连续运行 10 次，置信度有限。
4. 读接口返回话题后，没有做浏览器端手工验证编辑页。
