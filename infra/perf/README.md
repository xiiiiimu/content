# M8 性能实验（不进入默认 PR CI）

`PerfHarnessIT` 仅在 `-Dcontent.perf.enabled=true` 时启用。默认 CI **跳过**该用例；skipped ≠ passed。

固定实验台账见 `docs/m6-m8-batch.md`。脚本在本目录；结果写入 `target/perf-evidence/`。

## Ops / 计时 / 错误

| 名称 | 含义 |
| --- | --- |
| `feed_read` | 一次 `FollowingFeed.read` 墙钟延迟 |
| `like_write` | 一次 `InteractionService.like` 墙钟延迟 |
| `publish_visible` | 种子阶段 publish→inbox accept→worker→feed 命中（不计入稳态 ops） |
| `ops` | 稳态 `feed_read + like_write` |
| `errors` / `errorRate` | 稳态 op 抛错次数 / ops |
| `scanAmplification` | `page.scanned / max(1, items)` |
| `writeAmplificationTasksPerPushPost` | `DONE` 投递任务数 / PUSH 已发帖 |
| `backlog*` | `feed_delivery_tasks` 中 `NEW|RUNNING` |

## 固定配额（本地基线）

| 项 | 值 |
| --- | --- |
| JVM | Java 17，`-Xms512m -Xmx512m` |
| MySQL / Redis | Testcontainers 单节点 |
| 作者数 | 100（对照实验可先用较小 smoke：20） |
| 每作者帖子 | 50（smoke：10） |
| 读者数 | 200（smoke：40） |
| 关注图 | 每读者关注 20 作者 + 前 5 热点；采样读者 9001 关注全作者 |
| 读写比 | 写:读 = 1:9 |
| 热点互动 | 10% 请求打到 top-10 帖 |
| 模式对比 | 纯 PUSH / 纯 PULL / 混合（50/50 作者） |
| 种子顺序 | rebuild → 作者模式 → **先 follow** → drain → publish/accept/worker → 预热 → 稳态 |

## 运行

```powershell
# smoke（笔记本）
.\infra\perf\run-baseline.ps1 -Mode hybrid -DurationSec 20 -Authors 20 -PostsPerAuthor 10 -Readers 40 -FollowsPerReader 8
.\infra\perf\run-baseline.ps1 -Mode push   -DurationSec 20 -Authors 20 -PostsPerAuthor 10 -Readers 40 -FollowsPerReader 8
.\infra\perf\run-baseline.ps1 -Mode pull   -DurationSec 20 -Authors 20 -PostsPerAuthor 10 -Readers 40 -FollowsPerReader 8

# 汇总
.\infra\perf\summarize.ps1
```

输出：`target/perf-evidence/<mode>-<timestamp>/` 含 raw NDJSON、summary.json、git commit、配置快照。重复运行保留全部目录。
