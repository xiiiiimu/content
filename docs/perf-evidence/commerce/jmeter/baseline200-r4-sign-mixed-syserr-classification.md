# sign@200 / mixed@200 system-error classification (baseline200-r4)

对照证据：`bd3d7a8` / `baseline200-r4-*`。原始 JTL 保留。

## sign — 3 system errors

| Sampler | HTTP | Biz code | Exception / class | Count | First ts (local) | Elapsed |
|---|---|---|---|---:|---|---:|
| POST-original-sign | 503 | FEED_UNAVAILABLE | CannotGetJdbcConnectionException → FeedErrors | 3 | 2026-11-15T09:56:16+08 | 10369–11656 ms |

首个错误：`signOriginal` → `AuthoritativeVisibility.read` → `FeedMapper.visible` 取不到 JDBC 连接。

## mixed — 39 system errors

| Sampler | Class | Count | Notes |
|---|---|---:|---|
| MIX-purchase | HTTP_500 | 17 | settle 路径 MySQL Communications link failure |
| MIX-detail | HTTP_503 | 8 | 同 sign：JDBC 池耗尽 → FEED_UNAVAILABLE |
| MIX-purchase | HTTP_503 | 7 | 同上 |
| MIX-purchase | TIMEOUT (SocketTimeoutException) | 7 | 客户端 20s 内未完成 |
| MIX-poll / MIX-order | — | 0 | 未出现在失败样本 |

首批失败：`MIX-detail` HTTP_503 @ 2026-11-15T10:04:39+08（先于 purchase）。

## 对齐服务端日志

- `Feed unavailable` + `CannotGetJdbcConnectionException`（Tomcat 线程，sign/detail）
- `Application exception overridden by rollback` + `CommunicationsException: Communications link failure`（`SaleConsumer` settle，`SaleMapper.lockCampaign`；last packet ~5410 ms）
- 同期大量 `Delivery DEAD` / `CandidateIndexUnavailableException`（pipeline 扫 Redis，争抢连接）

## 根因（非单纯放宽客户端超时）

1. `FeedConfiguration.feedDataSourceBounds` 硬编码 JDBC `socketTimeout=5000` / `connectTimeout=5000`。长事务/争抢下 MySQL 掐断连接 → settle 500、客户端 TIMEOUT。
2. 负载下 Hikari 池与 pipeline delivery 争抢 → HTTP 503 FEED_UNAVAILABLE。
3. 客户端 TimeoutMs=20000 只是放大了超时可见性；根因是服务端 JDBC socketTimeout 与后台 pipeline 争用。

## 修复方向

- JDBC socket/connect timeout 可配置；commerce 启动设为 60s（保留 harness 默认 5s）。
- commerce JMeter 启动关闭 `content.pipeline.scan-enabled`（减少 delivery 争用）。
- Hikari max-pool 保持/略升；前后配置写入复跑证据。
