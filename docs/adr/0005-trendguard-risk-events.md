# ADR-005 TrendGuard 风险事件契约

状态：待 PR 审查（**不宣布 M9 完成**）。  
基线：SDD V2.1 §0 / §9 / §14 M9 / §15。本 ADR **不替代** §14 标题，只补齐原文未写死的落地细节。

---

## A. V2.1 原文要求（不得改写）

| 要求 | 出处 |
| --- | --- |
| M9 交付：「TrendGuard **风险事件契约**、**只读查询接口**、**独立集成验收**」 | §14 |
| 完整保留「TrendGuard **风险事件输出**」 | §9 |
| 不因短期目标删减 TrendGuard **集成** | §0 |
| 开放决策经 ADR 冻结 | §15 |
| 读取入口统一校验；缓存/Redis 不能授权（适用于仍返回内容的入口） | §2 C5、§8 |

原文**未规定**：生产者、触发条件、字段表、`eventId` 格式、HTTP 路径、分页、专用失败矩阵、检测算法/SLA。

---

## B. 新增实施决策（本 ADR）

### B1. 谁产生、在什么条件下产生

| 项 | 决策 |
| --- | --- |
| 生产者 | **内容平台进程内** `TrendingService`：在热点 `topic_event_ledger` **新插入成功**并更新分钟桶之后，同事务内评估并可能写出风险事件 |
| 非生产者 | 客户端 HTTP **无**风险写入 API；测试 **不得**仅靠插表/`record` 直调冒充「集成输出」验收 |
| 条件（首版） | 话题在滚动 **1h** 窗口内 `net_events` 合计 **≥** `content.trendguard.spike-threshold`（配置，默认 `3`）时，产出 kind=`TOPIC_SPIKE` |
| 非目标 | ML/反作弊模型、跨服务 Trend 微服务、Herd 源码兼容 |

### B2. 字段、版本、eventId、输出方式

| 字段 | 含义 |
| --- | --- |
| `schema_version` | 契约版本，首版恒为 **1**；响应带回，便于日后演进 |
| `event_id` | 幂等键；格式 `tg:1:{KIND}:{topic}:{bucketMs}`。`TOPIC_SPIKE` 的 `bucketMs` = 当前小时起点（`now/3_600_000*3_600_000`） |
| `topic` | 话题（≤128） |
| `kind` | 首版仅 `TOPIC_SPIKE`（其他 kind 保留扩展，写入校验 ≤32） |
| `occurred_at_ms` / `created_at_ms` | UTC 毫秒；首版写入时取当前时间 |
| `post_id` | 可选；触发评估时的互动帖（可空） |
| `detail` | ≤512；人类可读说明（如阈值快照） |

**输出方式**：写入 MySQL 表 `trend_risk_events`（权威）。不写 Redis 授权视图；不把风险事件当作帖子体放行依据。

### B3. 幂等与失败恢复

| 项 | 决策 |
| --- | --- |
| 幂等 | `event_id` PRIMARY KEY + `INSERT IGNORE`；同小时同 topic 重复触发不新增行 |
| 与热点同事务 | 风险写入与 ledger/bucket **同一 MySQL 事务**：互动已开事务时加入；失败则整笔互动回滚，客户端可重试 |
| 权威读失败 | 只读 API → 503 / `FeedFailure`；禁止用空列表伪装「已成功鉴权的无风险」若错误是基础设施故障（DataAccessException → 现有 503 映射） |
| 重建 | `trending.rebuild` **不删除**已落库风险事件（风险日志与分钟桶生命周期分离） |

### B4. 只读查询

| 项 | 决策 |
| --- | --- |
| 调用方 | 已认证的平台 API 调用方（运营/客户端只读观测）；**不是**匿名公网榜 |
| 鉴权 | 与现有 `/api/*` 相同：`Authorization: Bearer`（`FeedIdentityFilter` + controller 校验）；未认证 401 |
| 路径 | `GET /api/trendguard/risks` |
| 数据范围 | 窗口内**全部**风险事件（不按关注图过滤；事件不含帖子正文授权） |
| 参数 | `window=1h\|24h`（同 M5 毫秒约定）；可选 `topic`；`limit` 默认 50、最大 100；可选 keyset：`beforeMs` + `beforeEventId`（严格小于 `(occurred_at_ms, event_id)` 字典序续页） |
| 排序 | `occurred_at_ms DESC, event_id DESC` |
| 分页 | 单页最多 100；有下一页时客户端用末条的 `(occurredAtMs, eventId)` 作 `before*`；无 cursor 令牌（首版） |

### B5. 独立集成验收（禁止替身）

必须证明：**真实互动路径** → 平台**自动输出**风险事件 → 只读查询可见。  
禁止单独以测试调用 `TrendGuardService.record` 或 JDBC 插表作为 §14「风险事件输出」的通过证据（幂等单测可作为辅证，不计集成验收）。

---

## C. 审查核对（实现对照，不新增重复测试）

| 点 | 实现 | 已有覆盖 |
| --- | --- | --- |
| 事件原子性 | `InteractionService` 开事务 → `TrendingService.record`（同 `TransactionTemplate` / REQUIRED 加入）→ ledger/bucket/`recordInCurrentTx`；失败整笔回滚 | `TrendGuardRiskIT` 经 like 路径落库 |
| 并发去重 | `event_id` PK + `INSERT IGNORE`；同小时同 topic 不双写 | IT：第三 like 后 count 仍为 1 |
| 输出边界 | 仅写 `trend_risk_events`；不写 Redis 授权；查询返回 Risk 记录不含帖子正文放行 | IT + ADR B2；话题帖列表另属结转 |
| 查询权限 | Bearer + `TrendGuardController.user`；未认证 401；`FeedErrors` 已挂接 | IT unauthorized / authorized |

**已知剩余边界（READ_COMMITTED）**：两笔并发互动若各自事务内读到的 1h `net_events` 均仍低于阈值，可能延后到后续互动才发出 spike（PK 仍防双行）。长 `topic` 导致 `event_id` 截断至 64 字符的碰撞风险未专项验收。不新增并发 IT（避免与现有幂等断言重复堆砌）。

## D. 后果

- 话题帖列表（§8 / ADR-004 T10b）若保留，**不计入**本 ADR / M9 完成。  
- **不得宣布 M9 完成**直至审查与 main CI 通过且边界可接受。  

