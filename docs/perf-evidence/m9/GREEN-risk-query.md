# M9 工作区验证：风险事件输出 + 只读查询（**未宣布完成**）

- **前置**：`RED-risk-query.md` = 编译失败记录（≠ 业务 RED）  
- **行为反证**：`SUPPLEMENTAL-behavior-counterproof.md`（去掉 emit → IT 失败）  
- **针对性**：`mvn -B -ntp "-Dtest=TrendGuardRiskIT,TrendGuardIT,MySqlMigrationIT,InteractionsAndTrendingIT" test` → 8 run / 0 fail  
- **完整集成**：`mvn -B -ntp -Pintegration -Dcontent.perf.enabled=false verify` → **BUILD SUCCESS**；Tests run: **137**, Failures: 0, Errors: 0, Skipped: **2**（约 37m；日志 `integration-verify.log`）  
- **集成要点**：`TrendGuardRiskIT` 经 `InteractionService.like` → `TrendingService.record` → `emitSpikeIfNeeded` 落库，再 `GET /api/trendguard/risks`；**不以**测试直插表作为验收  
- **契约**：`docs/adr/0005-trendguard-risk-events.md`；`docs/m9-trendguard.md`  
- **状态**：**暂不宣布 M9 完成**  
