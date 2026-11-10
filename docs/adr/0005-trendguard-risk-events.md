# ADR-005 TrendGuard 风险事件契约

状态：**Accepted**（main `0872acc` / PR #10；M9 §14 已验收）。  
基线：SDD V2.1 §0 / §9 / §14 M9 / §15。本 ADR **不替代** §14 标题。

---

## A. V2.1 原文要求（不得改写）

| 要求 | 出处 |
| --- | --- |
| M9：「TrendGuard **风险事件契约**、**只读查询接口**、**独立集成验收**」 | §14 |
| 完整保留「TrendGuard **风险事件输出**」 | §9 |
| 不因短期目标删减 TrendGuard **集成** | §0 |
| 开放决策经 ADR 冻结 | §15 |

原文未规定生产者、字段、路径、角色模型。

---

## B. 实施决策

### B1. 生产者与条件

| 项 | 决策 |
| --- | --- |
| 生产者 | `TrendingService`：ledger **新插入**并更新分钟桶后，同事务内评估 |
| 条件 | 话题滚动 1h `net_events` ≥ `content.trendguard.spike-threshold`（默认 3）→ `TOPIC_SPIKE` |
| 非目标 | ML、亚分钟窗口、Herd/跨服务 |

### B2. 事件 ID 与业务唯一键

| 项 | 决策 |
| --- | --- |
| `event_id` | 确定性全文：`tg:1:{KIND}:{topic}:{windowStartMs}`，**禁止截断**；列 `VARCHAR(256)` |
| 业务唯一 | `UNIQUE (kind, topic, window_start_ms)` |
| 写入 | 普通 `INSERT`；仅将 **DuplicateKeyException** 视为幂等（返回 false）；其他 SQL 错误上抛，不得用 `INSERT IGNORE` 吞掉风险行 |

### B3. 同 topic 串行检测（并发）

| 项 | 决策 |
| --- | --- |
| 锁顺序 | 调用方已持有点赞/stats 等行锁 → ledger → `topic_minute_buckets` 行 → **`trend_topic_serial` FOR UPDATE（单 topic）** → risk insert |
| 机制 | `INSERT IGNORE` 仅用于确保 serial 行存在，再 `SELECT … FOR UPDATE`；只锁一个 topic，避免多 topic 交叉死锁 |
| 保证 | 并发累计达阈值后，后进入的事务在串行锁下看到已提交桶合计并 emit；**不依赖**后续新互动 |
| 事务 | 与互动共用默认 `PROPAGATION_REQUIRED`；回滚不留风险行 |
| 重建 | `trending.rebuild` 不删除风险事件 |

### B4. 只读查询与授权

| 项 | 决策 |
| --- | --- |
| 调用方 | **TrendGuard 运营 / 服务角色**（`TRENDGUARD_OPS`），**不是**普通读者 Feed |
| 鉴权 | 复用 `FeedIdentityFilter` + HMAC `Identity`；`role`∈{`READER`,`TRENDGUARD_OPS`}（缺省 `READER`） |
| 授权 | `GET /api/trendguard/risks` 要求 `ContentPrincipal.trendGuardOps()`；否则 **403**；无/无效 Bearer → **401** |
| 数据范围 | 窗口内风险事件列表；**不按关注过滤**（运营观测面）。字段为 topic/kind/score 类运营信号，不含帖子正文；正文可见性仍走 `VisibilityService` 其他入口 |
| 分页 | `limit`≤100；可选 `beforeMs`+`beforeEventId` keyset |

### B5. 验收

真实互动路径 → 自动输出 → ops 只读可见。禁止仅靠测试插表冒充输出验收。  
独立 IT：`TrendGuardRiskIT`；硬化 RED `5e6b730` → GREEN `606b1d2`。

---

## C. 已关闭缺陷（原审查边界）

| 原问题 | 处理 |
| --- | --- |
| `eventId` 截断碰撞 + IGNORE 漏事件 | 取消截断；业务 UNIQUE；DuplicateKey 显式处理 |
| 并发达阈后无新互动可能不产出 | per-topic serial `FOR UPDATE` |
| 「登录即可读」过宽 | 仅 `TRENDGUARD_OPS` |

---

## D. 后果

- 话题帖列表结转（§8 / T10b）**不计**作 §14 替代定义。  
- 不追加 M8 长跑 / ML / Herd。  
- M9 §14 三项交付已合入 main；保留边界见 `docs/m0-m9-acceptance.md`。  
