# M8 性能实验（不进入 PR 全量 CI）

固定实验台账见 `docs/m6-m8-batch.md`。脚本在本目录；结果写入 `target/perf-evidence/`。

## 固定配额（本地基线）

| 项 | 值 |
| --- | --- |
| JVM | Java 17，`-Xms512m -Xmx512m` |
| MySQL / Redis | Compose 默认单节点 |
| 作者数 | 100 |
| 每作者帖子 | 50（共 5_000） |
| 读者数 | 200 |
| 关注图 | 每读者关注 20 作者（幂律热点：前 10 作者额外被 80% 读者关注） |
| 读写比 | 写:读 = 1:9 |
| 热点互动 | 10% 请求打到 top-10 帖 |
| 模式对比 | 纯 PUSH / 纯 PULL / 混合（50/50 作者） |

## 运行

```powershell
# 1) 启动中间件并准备密钥后
.\infra\perf\run-baseline.ps1 -Mode hybrid -DurationSec 60
.\infra\perf\run-baseline.ps1 -Mode push -DurationSec 60
.\infra\perf\run-baseline.ps1 -Mode pull -DurationSec 60

# 2) 汇总
.\infra\perf\summarize.ps1
```

输出：`target/perf-evidence/<mode>-<timestamp>/` 含 raw NDJSON、summary.json、git commit、配置快照。重复运行保留全部目录。
