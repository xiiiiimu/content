# M9 验证：风险事件输出 + 只读查询（**已合入 main**）

- **前置**：`RED-risk-query.md` = 编译失败记录（≠ 业务 RED）  
- **行为反证**：`SUPPLEMENTAL-behavior-counterproof.md`（去掉 emit → IT 失败）  
- **硬化 RED→GREEN**：`5e6b730` → `606b1d2`（唯一性 / 并发串行 / ops 鉴权）  
- **完整集成（tip）**：`mvn -B -ntp -Pintegration -Dcontent.perf.enabled=false verify` → **142** run / 0 fail / **2** skip  
- **集成要点**：`TrendGuardRiskIT` 经 `InteractionService.like` → `TrendingService.record` → `emitSpikeIfNeeded` 落库，再 ops `GET /api/trendguard/risks`；**不以**测试直插表作为验收  
- **契约**：ADR-005 Accepted；`docs/m9-trendguard.md`；汇总 `docs/m0-m9-acceptance.md`  
- **合并**：PR #10 merge commit `0872acc`；main CI success  

