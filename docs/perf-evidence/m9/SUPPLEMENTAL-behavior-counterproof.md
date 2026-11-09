# 补充行为反证（**不是**历史上的先行业务 RED）

- **时刻**：2026-11-10  
- **方式**：`git worktree add E:\content-m9-counterproof HEAD`，同步当前工作区源码后，**仅**将 `TrendingService.emitSpikeIfNeeded` 置为空实现  
- **命令**：在工作树内 `mvn -B -ntp "-Dtest=TrendGuardRiskIT" test`  
- **结果**：可编译；Testcontainers 真实 MySQL/Redis；**断言失败**  
  - `[threshold reached via trending.record emit] expected: 1 but was: 0`  
  - 日志：`supplemental-counterproof-surefire.log`  
- **结论**：缺少生产输出链路时，集成 IT 会因**行为缺失**失败（满足「可编译的行为反证」）。  
- **明确**：本文件是纠偏后的**补充验证**，**不得**改写成「当初就有的 RED→GREEN 历史」。

主工作区实现未改；反证工作树用后移除。  
