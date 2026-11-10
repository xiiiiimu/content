# M9 TrendGuard（验收状态 · 以 V2.1 原文为准）

基线 merge：main `0872acc`（PR #10 merge commit；tip `606b1d2`）。  
本地原文：`C:\Users\西木\Downloads\内容平台_系统设计文档_V2.1_正确性契约强化版.pdf`（2026-11-07；已用 pypdf 全文抽取核对）。  
**仓库内无 PDF 副本 ≠ 本地无原文。** 不得仅凭 ADR-004 T10b 重定义 M9。  
**不再追加 M8 容量实验**（`docs/perf-evidence/capacity/QUEUE-PAUSED.md`）。

---

## 0. §14 验收结论（已合并）

| §14 要求 | 落地 | 证据 |
| --- | --- | --- |
| TrendGuard **风险事件契约** | ADR-005 + V10–V12；确定性 `eventId`；`UNIQUE(kind,topic,window_start_ms)` | ADR-005；`TrendGuardService` / migrations |
| **只读查询接口** | `GET /api/trendguard/risks`；`TRENDGUARD_OPS` 专用（401/403） | `TrendGuardController`；`TrendGuardRiskIT` |
| **独立集成验收** | 真实 MySQL/Redis Testcontainers；生产 like→emit→ops 查询路径 | `TrendGuardRiskIT`（6）；hardening RED `5e6b730` → GREEN `606b1d2` |
| §9 风险事件**输出** | `TrendingService.emitSpikeIfNeeded` 与 ledger/bucket 同事务 | 生产路径 IT；反证 `SUPPLEMENTAL-behavior-counterproof.md` |

**结论：M9（V2.1 §14 + ADR-005）已在 main `0872acc` 验收通过。**  
话题帖列表可见性（§8 / T10b）为结转，**不替代** §14 标题。

| 验证 | 结果 |
| --- | --- |
| 本地 full verify（tip） | 142 run / 0 fail / 2 skip |
| PR tip CI | [42710696838](https://github.com/xiiiiimu/content/actions/runs/42710696838) success @ `606b1d2` |
| main CI | [42726581884](https://github.com/xiiiiimu/content/actions/runs/42726581884) success @ `0872acc` |

---

## 1. V2.1 原文：TrendGuard 是什么

| 职责 / 要求 | 原文要点 | 出处 |
| --- | --- | --- |
| 里程碑交付 | **「TrendGuard 风险事件契约、只读查询接口、独立集成验收」** | §14 M9（p.6） |
| 不得裁剪 | 不因短期目标删减「媒体、互动、热点、可靠性与 **TrendGuard 集成**」 | §0（p.1） |
| 风险事件输出 | 「完整保留媒体、互动、热点、**TrendGuard 风险事件输出**，不因时间约束裁剪」 | §9（p.4） |
| 业务借鉴 | 业务概念借鉴 Cornell Herd Hot Topics；ELK 仅可选 UI 参考，**不预设源码兼容** | 定位段（p.1） |
| 开放决策 | 协议未定项须 **ADR 单独冻结**（含热点窗口边界精度等） | §15（p.6） |

V2.1 **没有**给出：风险事件字段表、HTTP 路径、专用失败降级表、检测算法/SLA。字段级决策见 ADR-005。

---

## 2. 与「热点榜 / 话题页」的边界（勿混入 M9）

| 能力 | V2.1 归属 | 出处 |
| --- | --- | --- |
| ledger 去重、分钟桶、可重建 Redis ZSET、滚动 1h/24h | **M5** | §9、§14 M5 |
| `GET /api/topics/trending`（`window` / `metric`） | **API 冻结表（热点读）**，随 M5 | §10 |
| 话题页等读取入口统一 `VisibilityService` | **全局可见性 C5 / §8**；ADR-004 将内容列表过滤记在 **M5 T10b** | §2 C5、§8；ADR-004 |

→ **M9 ≠ 补完 T10b 话题帖列表。** T10b 结转已随 PR #10 落地，但不改写 §14 完成定义。

---

## 3. 实现映射

| 实现 / 测试 | 映射到 | 结论 |
| --- | --- | --- |
| `TrendingService.emitSpikeIfNeeded` → `trend_risk_events` | §9 输出 + ADR-005 | **通过** |
| `GET /api/trendguard/risks` + ops 鉴权 | §14 只读查询 | **通过** |
| `TrendGuardRiskIT` | §14 独立集成验收 | **通过** |
| `GET /api/topics/{topic}/posts` + `TrendGuardIT` | §8 / T10b 结转 | **结转通过**；不计作 §14 替代 |
| M8 长跑 | §14 M8 | **不追加** |

---

## 4. RED / 反证（诚实）

| 项 | 事实 |
| --- | --- |
| 早期 `RED-risk-query.md` | **编译失败**记录；**不满足**业务 RED |
| 补充行为反证 | `SUPPLEMENTAL-behavior-counterproof.md`（去掉 emit → IT 失败） |
| 审查硬化业务 RED→GREEN | `5e6b730` → `606b1d2`（唯一性 / 并发串行 / ops 鉴权） |

---

## 5. 保留边界（非目标）

- 亚分钟窗口 / ML / Herd 源码或 Collector 兼容  
- 追加 M8 容量长跑 / 宣称最高容量  
- 独立 Trend 微服务、改 delivery 租约/token/DEAD  

汇总见 `docs/m0-m9-acceptance.md`。
