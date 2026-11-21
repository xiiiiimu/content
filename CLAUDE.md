# CLAUDE.md

## 项目
创作者素材社区。Java 17 + Spring Boot 3 + MyBatis + MySQL + Redis + RocketMQ，对象存储走 S3 兼容接口（目标是 MinIO，见“已知边界”）。
- Java 在 Windows 原生运行。本地开发用的中间件跑在 Docker Desktop（`infra/docker/compose.yml`）；集成测试由 Testcontainers 自己起容器，不依赖 compose 栈。
- JDK：`C:/Users/西木/.jdks/ms-17.0.17`
- Maven：`tools/apache-maven-3.9.6`

## Git 与证据
- 保留所有未提交改动、暂存区状态和历史证据。
- 未经明确要求，不提交、不推送、不合并。
- 不覆盖、不重写已有证据文件。每次跑测都用新目录、新活动、新 clientKey。
- 私有 Token、CSV、meta 只放在 `target/`，不进入 `docs/`。

## 测试纪律
- 修改业务行为先写 RED，再写 GREEN。合格的 RED 必须同时满足：编译通过、环境健康、因缺少目标行为而失败。
- 环境或夹具问题导致的失败不算 RED，失败日志要保留。
- 不靠放宽超时、扩大线程池或跳过 preflight 让测试通过。

## 测试运行方式
- 本地集成测试用 `infra/test/run_it_batches.py` 分批运行（每批一到两个 IT 类）。
- 真实 MQ 测试（`CommerceRealMqIT`、`CommerceDispatchBatchRealMqIT`）需设环境变量 `CONTENT_REAL_MQ_IT=true`，由 Testcontainers 启动 RocketMQ 5.3.2（`CommerceMqFixture`，broker 固定使用宿主机端口 30924），不再连接本机 compose 里的 broker。
- FFmpeg 通过 Docker 镜像 `linuxserver/ffmpeg:version-7.1-cli` 调用，不需要宿主机安装 ffmpeg。
- 其他 media IT 用 `adobe/s3mock` 作 S3 替身。`MediaMinioIT` 需 `-Dcontent.minio.it=true` 和本地自建镜像 `content-minio:RELEASE.2025-04-22T22-12-26Z`，CI 中跳过。
- `PerfHarnessIT` 需 `-Dcontent.perf.enabled=true`，不进 CI。`AssistantEvalE2EIT` 需 `-Dassistant.eval.cases` 和 `-Dassistant.eval.out`，否则跳过。
- CI（`.github/workflows/ci.yml`）是并行 job，每个超时不超过 35 分钟（前端 15 分钟）；超了就再拆分，不调大超时。

## 压测
- 正式压测时关闭客户端 JFR、线程快照和重型监听器。
- 缺少证据的指标标注“缺证据”，不做推算。

## 已知边界
- 支付和退款都是模拟实现（见 README 和 `docs/v1.1-material-commerce.md` 的“支付与退款边界”）。
- FFmpeg 超时或线程中断后，容器会被 `docker rm -f` 清理（第 D 轮 D2 已验证，`FfmpegTranscoderTimeoutIT`）。遗留：容器尚未创建时就超时的理论窗口。
- 官方 MinIO 镜像无法获取，M6 的官方 MinIO 验收仍未关闭。
