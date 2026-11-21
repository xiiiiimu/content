# Commerce load JVM/config before vs after (sign/mixed fix)

## Before (bd3d7a8 / baseline200-r4)
- CONTENT_HIKARI_MAX_POOL=80
- JDBC socketTimeout hard-coded 5000 ms (FeedConfiguration)
- content.pipeline.scan-enabled=true (default)
- Lettuce DefaultClientResources io/computation = 2/2
- purchase accept path sync RocketMQ send before HTTP 200
- Residual: sign 3×503, mixed 39 (503/500/TIMEOUT) — MIX-purchase dominated timeouts

## After (r5 → r6)
- CONTENT_HIKARI_MAX_POOL=120
- CONTENT_HIKARI_JDBC_SOCKET_TIMEOUT_MS=60000
- CONTENT_HIKARI_JDBC_CONNECT_TIMEOUT_MS=10000
- CONTENT_HIKARI_CONNECTION_TIMEOUT_MS=15000
- CONTENT_MYBATIS_STATEMENT_TIMEOUT_SECONDS=30
- CONTENT_REDIS_IO_THREADS=8 / CONTENT_REDIS_COMPUTATION_THREADS=8
- --content.pipeline.scan-enabled=false
- --content.sale.consume-threads=20 (48 made mixed worse via lockCampaign contention)
- --content.sale.scan-delay-millis=50 (accept path defers MQ to SalePendingScanner)
- SaleService.purchase: async path no longer blocks on producer send
- JMeter threads/duration/timeout unchanged: 200 / 20s (steady) / Loops=1 ramp=8 (mixed) / TimeoutMs=20000

## Intermediate evidence (retained)
- r5: JDBC/pipeline fix → sign sys=0; mixed 4× MIX-purchase Read timed out
- r5b: consume-threads=48 → mixed 20 sys (19 TIMEOUT + 1×503) — reverted
