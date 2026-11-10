# M9 早期缺失记录：编译失败（**不满足业务 RED**）

- **时刻**：2026-11-10（本地，分支 `codex/m9-trendguard`，当时工作区未提交）
- **命令**：`mvn -B -ntp "-Dtest=TrendGuardRiskIT" test`
- **结果**：`testCompile` 失败 —— 找不到符号 `TrendGuardService`
- **日志**：`red-risk-query-surefire.log`

## 定性（如实）

| 说法 | 是否成立 |
| --- | --- |
| 证明当时尚无 `TrendGuardService` 类型 | 是 |
| **业务 RED**（可编译、真实中间件、因**行为缺失**导致断言失败） | **否** |
| 测试先行 / RED→GREEN 纪律 | **否**（先有编译期引用，不是跑红的行为验收） |
| 可回写为「历史先行 RED commit」 | **否**（不伪造 git 历史） |

本文件保留为过程偏差证据，**不得**在验收叙述中写成业务 RED。

行为级补充反证见：`SUPPLEMENTAL-behavior-counterproof.md`（隔离工作树；标明非历史 RED）。
