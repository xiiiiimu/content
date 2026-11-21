# A11 助手指令 Java 端校验：评测复跑（2026-11-21）

原则：无论解析器输出什么，Java 都不执行不合法的指令。凡是 Java 能自己核实的事项，都从数据库独立校验，不依赖解析器的字段：
- 目标评论是本帖的反馈评论（有素材版本），且不是助手写的；
- 作者不会成为候选人，每个账号只有一个参与资格；
- 商用授权在素材许可范围内；
- 奖品是本帖已就绪的版本；
- 中奖人数、截止时间和开始时间合法。

只有解析器才能知道的语义，用显式字段表达：
- 订阅的 `action` 为 `SUBSCRIBE` 或 `CANCEL`；
- 订阅的 `target` 为 `SELF`、`OTHERS` 或 `ALL`；
- 不支持的条件放进 `unsupportedTools`。

解析器的时间识别能力**没有改动**（是否接入大模型是下一步的决定）。

原始数据只在 `target/assistant-eval/a11-20261121T033523Z/`（dev、test、all、smoke-r1）和 `target/hardening-a/`（RED/GREEN 日志）。对照基线为 r3：`baseline-20261121T021628Z`（`LATEST_BASELINE` 已指向它）。

## 1. 运行方式
- 解析器：`assistant-python/app/main.py`（A11 版）。
- 请求的 `context` 与 A11 后的 Java 一致：`parentKind`/`parentIsFeedback`、`nowMs`、`timezone`、`postLicenseCommercial`、`enabledTools`。
  - 首次复跑时 `eval/run_eval.py` 还在另一个会话名下，仍发 `context={}`，因此用一个包装脚本（`target/hardening-a/a11-e2e-tools/run_eval_java_ctx.py`）只替换请求里的 `context`。
  - 收尾时这段逻辑已直接写进 `eval/run_eval.py`（`java_context()`），包装脚本已删除。用修改后的脚本重跑 dev/test/all/smoke-r1（`target/assistant-eval/a11-runeval-20261121T041927Z/`）：predictions、errors、metrics、e2e-cases 与包装脚本的结果逐字节相同，端到端的输入没有变化，本文数字不变。
- 端到端：`AssistantEvalE2EIT`，按录制的解析结果回放，走真实的 Java 链路（含 A11 校验）。汇总用 `eval/e2e_summary.py`。
- 补充集 `smoke-r1`：用新解析器重新解析 r1 冒烟的 171 条原文（epoch 时间写法，旧标注）。r3 数据集里的抽奖全部因自然语言时间停在补问，抽奖命令到不了 Java；只有冒烟集能检验 Java 在抽奖路径上的拦截。冒烟集的标注是旧版，只用来统计非法执行，不用来算准确率。

## 2. 端到端非法执行

| 运行 | 基线 | A11 |
|---|---|---|
| r3 全量 281 条 | **10**（全部是订阅：a110–a113、a115–a120） | **0** |
| r1 冒烟 171 条 | **18**（6 条订阅、12 条抽奖：a188、a189、a191、a192、a194、a199、a202、a207–a211） | **0** |

不误伤，gold 为 EXECUTE 的命令执行数与基线相同：
- r3：订阅 14/25；
- 冒烟：订阅 10/18，抽奖 5/28。

剩余未执行的都是解析器本身的问题（兜底成搜索、时间解析），本轮不改。

任务终态分布：
- r3：DONE 137 / CLARIFY 144 → DONE 130 / CLARIFY 131 / REJECTED 20；
- 冒烟：基线有 3 条停在 NEW（Java 抛异常，等待重试，没有回复）；A11 后变为 REJECTED，并回复原因。

Java 拦截原因（冒烟）：
- 替他人或批量订阅：2；
- 取消意图且本来没有订阅（不变更，回复“无需取消”）：2；
- 中奖人数不合法：2；
- 截止时间已过：1；
- 素材不允许商用：1（a207，只能由 Java 拦截）。

其余非法样例（不支持的条件、至少 N 项大于条件数、目标不是反馈）在解析器一层就已经变成补问并标出 `unsupportedTools`。即使解析器漏掉，Java 也会拦截：`AssistantCommandValidationIT` 直接构造解析结果验证了这一点。

## 3. 解析器指标（与 r3 基线对比）

| 指标 | dev 基线 → A11 | test 基线 → A11 |
|---|---|---|
| 意图准确率 / 宏 F1 | 84.3% / 0.670 → 不变 | 82.1% / 0.671 → 不变 |
| 执行判定准确率 | 64.5% → 65.5% | 58.3% → 60.7% |
| 补问 precision / recall | 64.7% / 73.3% → 65.5% / 76.0% | 60.0% / 65.6% → 62.2% / 71.9% |
| 不支持条件标进 `unsupportedTools` | 0/7 → **7/7** | 0/3 → **3/3** |
| 解析层“不该执行却产出命令” | 40 → 38 | 21 → 19 |
| REJECT 行解析层未产出命令 | 18/38 → 18/38 | 6/16 → 6/16 |

**怎么读这些数：**
- “取消”和“替他人/批量”在解析层仍然产出订阅命令，只是带上 `action=CANCEL` 或 `target=OTHERS/ALL`。是否执行由 Java 决定，所以 REJECT 行的解析层计数不变，端到端非法执行为 0。
- a110–a113 现在因 `parentKind` 不是反馈而补问，补问 recall 因此上升。
- 意图类指标、搜索字段、抽奖字段都没有变化：本轮没有改关键字和时间识别。

## 4. 测试
- **Java RED**：`AssistantCommandValidationIT`，42 项中 38 项失败（`target/hardening-a/a11-red3-*`）。失败分两类：
  - **真的被执行了**：a207、a209 的非数字奖品提示、规则节点中的未知参数（a202/a210/a211 的表达方式）、订阅目标不是反馈或不属于本帖、替他人/批量订阅、取消意图、开始时间晚于截止时间、未知订阅操作；
  - **已被拦截，但任务抛异常重试、没有回复**：a199、a209 的他帖版本、非作者发起、中奖人数、截止时间、未开放工具。另有 `unsupportedTools` 一类：已被拦截，但状态是 DONE，而不是 REJECTED。
  - 另外 4 项一开始就通过：两项对照、商用素材发商用、作者不入候选与每人一次（Java 原本就保证）。
  - 第一次运行因夹具问题失败（用户未关注作者，帖子不可见），日志保留在 `a11-red-112838`，不算 RED。
- **GREEN**：
  - 新 IT 与相关 IT 全部通过：`AssistantCommandValidationIT`、`AssistantFlowIT`、`AssistantTaskAccessIT`、`CommerceIngressRevisionIT`、`MaterialCommercePurchaseIT`、`MaterialCommerceRefundBlacklistIT`、`MySqlMigrationIT`、`ReportDedupMigrationIT`（`a11-green2-*`、`a11-green3-*`）。
  - 第一次 GREEN 撞上 `assistant_tasks` 的 CHECK 约束，因此新增 V30，把 REJECTED 加进允许的状态。
- **pytest**：RED 22 项失败、5 项通过；GREEN 27 项通过。过时的 `test_purchase_not_silently_ignored` 改为 `test_purchase_enters_rule_tree`，另加了“不支持条件必须出现在 unsupportedTools”的参数化测试。CI 新增 `assistant-python` job 运行 pytest。
