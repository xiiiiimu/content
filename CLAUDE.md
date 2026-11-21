# CLAUDE.md

## 项目
创作者素材社区。Java 17 + Spring Boot 3 + MyBatis + MySQL + Redis + RocketMQ + MinIO。
- Java 在 Windows 原生运行，中间件跑在 Docker Desktop。
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

## 压测
- 正式压测时关闭客户端 JFR、线程快照和重型监听器。
- 缺少证据的指标标注“缺证据”，不做推算。

## 已知边界
- 支付和退款都是模拟实现。
- FFmpeg 超时后的容器清理尚未验证。
