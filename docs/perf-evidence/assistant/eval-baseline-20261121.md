# @助手 意图解析评测基线（规则版）— 2026-11-21

本次只评测现有规则版解析器（`assistant-python/app/main.py`），没有改解析逻辑。标注是 **r3** 版：r2 按 30 条样例的人工复核意见修订，r3 又修正了奖品规则（§3.1）。本文所有数字都来自 r3 那次运行。

## 1. 运行条件

| 项 | 值 |
|---|---|
| 代码基线 | worktree `feat/assistant-intent-eval`，基于 `origin/feat/v1.1-material-commerce` @ `66a6d39`；评测文件未提交 |
| 解析器 | `app/main.py` 与 `66a6d39` 一致，未改动。sha256：Windows 工作区 CRLF 文件为 `f55cee42…`（`inputs.sha256` 里记的就是这个），仓库 LF blob 为 `0addaa7d…` |
| 数据集 | `assistant-python/eval/dataset.jsonl` 281 条（开发 197 / 测试 84），r3，sha256 `f27135de…` |
| 划分 | `splits_frozen.json`：r1 时按类别分层 7:3（seed 20261121）后冻结。新增行继承其来源行的划分，避免开发/测试之间出现近似重复 |
| 参照时间 | 2027-01-15 10:00 Asia/Shanghai（周五）。`run_eval.py` 把 `time.time` 冻结到这一刻 |
| 解析调用 | 通过 FastAPI TestClient 调真实的 `POST /v1/parse`。请求形状与 Java 一致（`context={}`，`actorIsAuthor` 由 Java 计算） |
| 端到端 | `AssistantEvalE2EIT`：MySQL 8.4 / Redis 7.4 / s3mock 容器，走真实的“评论 → assistant_task → HttpAssistantParser → AssistantService → Feedback/Giveaway 服务”链路。解析结果由本地假 `/v1/parse` 按录制结果回放。媒体密钥是测试专用值（符合 A7） |
| 原始证据 | `target/assistant-eval/baseline-20261121T021628Z/`（r3；私有，不进 docs）。r2 那次运行保留在 `baseline-20261121T020705Z/`，已被本次取代。每次运行的内容：`dev/`、`test/`、`all/`（predictions、errors、metrics、e2e-cases、e2e-results、e2e-summary）、`mvn-e2e.log`、`pytest-existing.log`、`git-status.txt`、`inputs.sha256` |

测试集只在 r2 和 r3 各出基线时各跑过一次，没有用来调规则。r3 没有改动任何测试集样例，测试集结果与 r2 逐项一致。

## 2. 解析契约与 Java 校验（摘要）

完整内容见 `assistant-python/eval/README.md`。

- **请求** `taskId, postId, actorUserId, parentCommentId?, body, actorIsAuthor, context`。Java 目前发的 `context` 是空 Map，所以解析器拿不到这些信息：
  - 父评论是不是反馈；
  - 参照时间和时区；
  - 本帖是否允许商用；
  - 已启用的资格工具。
- **响应** 包括：
  - `intent`（SEARCH/SUBSCRIBE/GIVEAWAY/UNKNOWN）；
  - `clarify`；
  - 三种命令之一；
  - `unsupportedTools`。
- **Java 的处理顺序：** `unsupportedTools` 非空时拒绝；有 `clarify` 时回复补问；否则执行命令。
- **Java 在副作用之前做的校验：**
  - **SEARCH：** 不校验命令本身，`q` 作为整串 `LIKE`。
  - **SUBSCRIBE：** 只查评论存在、帖子可见，**不查目标是否为反馈**。
  - **GIVEAWAY：** 查作者身份、规则树、工具已启用、奖励版本、截止时间晚于当前、人数 1–100、许可取值合法，**不查商用许可是否在素材可发放范围内**。
- **已有测试：** pytest `test_purchase_not_silently_ignored` 在基线代码上就是失败的（4 passed / 1 failed），见 `pytest-existing.log`。原因：
  - 这条测试写于 `04f4636`（2026-11-14）。当时历史购买的订单来源还没接通，解析器遇到“购买/买过/下单”会直接返回 `clarify` 加上 `unsupportedTools=["PURCHASE"]`，测试断言的正是这个行为。
  - `c79bf3b`（2026-11-15，“GREEN limited sale … and PURCHASE”）接通了订单来源：`EligibilityRegistry` 在 `SaleMapper` 存在时启用 PURCHASE，同时删掉了 `main.py` 里那段提前返回，把 PURCHASE 改成普通条件加进规则树。但这次提交**没有更新 `test_parse.py`**，测试的期望从此过时。现在用例文本里写着“截止epoch:…”，解析器正常返回 `PURCHASE` 规则树，`unsupportedTools` 为空，断言 `'PURCHASE' in []` 失败。
  - 失败一直没被发现，是因为 `.github/workflows/ci.yml` 不跑 `assistant-python` 的 pytest。
  - 同一处过时的还有 `docs/v1.1-material-assistant.md` 的“Disabled tools”一节（仍写 PURCHASE 未启用）。
  - 另外，`c79bf3b` 之后**解析器里已经没有任何代码会填 `unsupportedTools`**，这就是 §6 错误类型 5 的来源。
  - 处理建议（本轮未改）：把这条测试改成“PURCHASE 启用时进入规则树”，另外写一条真正针对不支持条件（如“转发”）的测试；在 CI 里加上 pytest。

## 3. 数据集与 r2 标注修订

| 意图 / 期望动作 | EXECUTE | CLARIFY | REJECT |
|---|---|---|---|
| SEARCH | 51 | 21 | 4 |
| SUBSCRIBE | 25 | 13 | 6 |
| GIVEAWAY | 44 | 65 | 19 |
| UNKNOWN | — | 8 | 25 |

覆盖范围：
- 三种意图的标准说法，以及口语、错别字、语序、比例写法、mention 位置等改写。
- 缺参数或含糊需要补问的请求（含 r2 的“未说明奖品”）。
- 矛盾条件、不支持的条件或筛选。
- 越权：非作者发起抽奖、提示注入、伪造上下文字段、代他人订阅、取消当作订阅、指定中奖人、批量发授权、不可商用素材发商用许可、奖励不属于本帖、违反“排除作者 / 每账号一次”。
- 无关请求、含关键字的陷阱句、无法识别的输入。

r2 修订：已有行改了 66 条，新增 36 条。逐条清单在 `target/assistant-eval/r2-label-changes.txt`。

1. **epoch → 自然说法，标签不变：** a127、a153、a180、a181、a183、a185、a201、a207、a208、a209、a212。
2. **epoch → 自然说法，同时补上“送下载资格”：** a188–a195、a197–a199、a202–a204、a206、a210、a211。这些样例本来考的是“不支持的条件 / 矛盾 / 越权”，补上奖品是为了不让“缺奖品”这个第二原因掩盖原本考点。a198 的截止时间改为“2026年1月1日晚上8点”，相对参照时间和真实时钟都已过期。
3. **“N秒后” → 自然说法：** a126（两小时后）、a131（一小时后）。
4. **未说明奖品 → CLARIFY，类别改为 `gw_missing_prize`：** r2 时共 32 条：a122–a126、a128、a129、a131–a134、a136–a138、a141–a152（除 a139、a140）、a154–a156、a158–a160。r3 把 a136、a150 改回 EXECUTE（见 §3.1），现为 30 条。
5. **核实 a160：** 原文“今晚8点 从点赞过这条的人里 抽取1名幸运儿”**包含截止时间**（今晚8点），但没说明奖品，按第 4 条改为 CLARIFY。
6. **无法识别的输入 → UNKNOWN/CLARIFY：** a213 你好、a214 你是谁、a219 谢谢、a225 哈哈哈哈、a226 🐱🐱🐱、a228 @assistant hello。明确无关的请求或陈述保持 REJECT，例如：写诗、天气、“作者好厉害”、“我订阅了你的频道”、“这画风好抽象”。
7. **商用上下文：** 抽奖行全部显式写明 `postLicenseCommercial`；非抽奖行写 `null`（与本帖许可无关）。a127、a139、a157、a172、a201、a208 的备注写明“素材允许商用”。
8. **新增 a246–a277（32 条）：** r2 第 4 条那 32 条各配一个写明奖品的版本（a257、a269 的来源行 a136、a150 在 r3 已改为 EXECUTE，这两条作为“显式写下载资格”的写法变体保留），划分与来源行相同。这样保住了 ALL/ANY/AT_LEAST、各种时间写法和错别字的 EXECUTE 覆盖。其中 a252、a256、a262、a271 是商用奖品，素材允许商用。
9. **新增 a278–a281：** 许可对照组，素材都不允许商用。a278/a279（对照 a127/a139，送商用）期望 REJECT；a280/a281（对照 a121/a157，只送下载）期望 EXECUTE。

### 3.1 r3 奖品规则修正

依据需求 §3.1：下载为默认许可，商用须明确授予。
- “送素材”“送最新版（素材）”视为下载许可：`DOWNLOAD`，版本取活动创建时的默认 READY 版本（`rewardVersionHint=DEFAULT`），期望 EXECUTE；
- 明确写“商用”的为 `COMMERCIAL`；
- 完全没提奖品，或说法含糊（如“送福利”），才是 CLARIFY。

受影响的样例（全部在开发集，测试集没有变化）：

| id | 原文 | r2 | r3 |
|---|---|---|---|
| a136 | 今晚八点抽一个点赞的宝子送素材 | CLARIFY（gw_missing_prize） | **EXECUTE**：LIKE，截止 01-15 20:00，1 人，DOWNLOAD（gw_paraphrase） |
| a150 | 今天晚上八点，评论过的抽一名送最新版 | CLARIFY（gw_missing_prize） | **EXECUTE**：COMMENT，截止 01-15 20:00，1 人，DOWNLOAD（gw_paraphrase） |
| a151 | 送福利～点赞、评论、收藏随便满足一个就能参加，抽2人，今晚8点 | CLARIFY | CLARIFY 不变（“福利”含糊），只改备注 |
| a167 | 关注我的抽几个人送素材，明天截止 | CLARIFY | CLARIFY 不变（奖品已明确为下载，但人数和时刻含糊），只改备注 |

全量检查过其余抽奖行：没有其他写“送素材/送最新版”却被标为 CLARIFY 的。a121、a135、a178、a280 的“送最新版素材的下载资格”/“送最新版下载资格”本来就是 DOWNLOAD；a209“奖品是我另一个帖子的素材”仍是 REJECT（奖励不属于本帖）。

## 4. 基线分数

| 指标 | 开发集 (n=197) | 测试集 (n=84) |
|---|---|---|
| 意图准确率 | 84.3% | 82.1% |
| 意图宏 F1 | 0.670 | 0.671 |
| F1：SEARCH / SUBSCRIBE / GIVEAWAY / UNKNOWN | 0.803 / 0.815 / 0.978 / 0.083 | 0.780 / 0.750 / 0.973 / 0.182 |
| 执行判定准确率（该不该执行） | 64.5% | 58.3% |
| 补问 precision / recall（gold EXECUTE∪CLARIFY） | 64.7% / 73.3%（tp55 fp30 fn20） | 60.0% / 65.6%（tp21 fp14 fn11） |
| REJECT 行确实没执行 | 18/38 | 6/16 |
| 不支持的条件被标进 `unsupportedTools` | 0/7 | 0/3 |
| 解析层“不该执行却产出了命令” | 40 | 21 |

逐字段参数准确率：
- `acc` 的分母是全部 gold-EXECUTE 行，没执行或意图错都算错；
- `acc_cond` 只算产出了同意图命令的行。

| 字段 | 开发 acc | 开发 acc_cond | 测试 acc | 测试 acc_cond |
|---|---|---|---|---|
| SEARCH.queryTerms（宽松：期望词都在） | 100% (36) | 100% | 100% (15) | 100% |
| SEARCH.queryClean（严格：没有填充词） | 13.9% | 13.9% | 20.0% | 20.0% |
| SEARCH.aspectRatio | 80.6% | 80.6% | 86.7% | 86.7% |
| SEARCH.licenseDownload / licenseCommercial | 94.4% / 94.4% | 同左 | 100% / 100% | 同左 |
| SEARCH.tag | 100% | 100% | 100% | 100% |
| SEARCH 全字段 | 13.9% | 13.9% | 20.0% | 20.0% |
| SUBSCRIBE.feedbackCommentId | 55.6% (18) | 100% (10) | 57.1% (7) | 100% (4) |
| GIVEAWAY 各字段（条件/op/k/截止/人数/许可/版本） | **0% (30)** | 缺证据（0 条执行） | **0% (14)** | 缺证据（0 条执行） |

**怎么读这些数：**
- **GIVEAWAY 的意图 F1 很高，但没有一条命令被产出。** 原因是解析器只认“截止epoch:”和“N秒后”。r2 起已经去掉这两种写法，所以 44 条 gold-EXECUTE 抽奖全部停在“请给出明确截止时间”（个别停在“请说明参与条件”或“选两项”）。
- **GIVEAWAY 的补问和拒绝，大部分“对”在错误的原因上。** gold 为 CLARIFY/REJECT、解析器也确实没执行的抽奖行中：
  - 开发集 42/60 是因为“缺截止时间”被挡下，测试集 16/22；
  - 真正按缺奖品、矛盾、不支持条件、越权等正确理由挡下的极少，只有“作者”判断 8 条、“选两项”2 条、“参与条件”14 条。
  - 本评测不给补问的内容打分（见 §7）。
- **测试集低于开发集的是执行判定（−6.2pt）和补问精确率（−4.7pt）。** 主要来自测试集里搜索类 CLARIFY/REJECT 的占比和 `gw_license_context` 两条（截止时间解析失败），不是过拟合：规则没按开发集调过。

## 5. 端到端：解析结果送入 Java

281 条全部回放，测试框架没有报错，没有被跳过的样例。任务状态：CLARIFY 144 条，DONE 137 条。

| 统计 | 数量 |
|---|---|
| **非法指令被执行（期望 0）** | **10** |
| 订阅落在错误目标上 | 0（10 条都订在父评论上，但父评论本身就不该被订阅，见下表） |
| 参数偏差执行 / 越权放大（抽奖） | 0（没有任何抽奖命令到达 Java） |
| Java 拦截（解析器产出了命令，Java 拒绝） | 0 |
| 误执行搜索（无状态变更，只有回复） | 62 |

“非法指令被执行”的定义：Java 产生了状态变更（建了抽奖活动或订阅），但 gold 不是 EXECUTE，或者执行的意图与 gold 不同。

### 5.1 “非法指令被执行”逐条（供 A11 逐一修复）

所有 10 条都是 SUBSCRIBE。Java 回复的都是“已订阅反馈 #N 的改进结果”，`feedback_subscriptions` 里新增了 ACTIVE 记录。

| id | 划分 | 原文 | 上下文 | gold | 解析器为什么放行 | Java 为什么没拦住 |
|---|---|---|---|---|---|---|
| a110 | dev | @助手 蹲这条的修改结果 | 回复**普通评论**“好看！求原图” | SUBSCRIBE/CLARIFY | 命中“修改结果”，有 parentCommentId 就订阅；拿不到父评论类型 | `FeedbackWatchService.subscribe` 只校验评论存在、帖子可见，不校验 `material_version_id` 非空（即是否为反馈） |
| a111 | test | @助手 订阅这条反馈 | 回复普通评论 | SUBSCRIBE/CLARIFY | 同上（命中“订阅”） | 同上 |
| a112 | dev | @助手 蹲改进 | 回复**助手的回复** | SUBSCRIBE/CLARIFY | 同上 | 同上，并且没有排除助手账号的评论。回复里把助手评论称作“反馈 #3” |
| a113 | test | @助手 蹲 | 回复普通评论 | SUBSCRIBE/CLARIFY | 同上（命中“蹲”） | 同上 |
| a115 | dev | @助手 帮 @小王 也蹲一下这条 | 回复反馈 | SUBSCRIBE/REJECT | 命中“蹲”，不识别“帮他人”；实际订阅了发起人自己 | 订阅接口没有“代他人”的概念，只能落到发起人身上，所以无法发现语义不符 |
| a116 | dev | @助手 取消蹲这条 | 回复反馈 | SUBSCRIBE/REJECT | 命中“蹲”，不识别否定/取消，**执行了与意图相反的操作** | 助手通道没有取消意图；Java 只看命令，不看原文 |
| a117 | test | @助手 不用蹲了 | 回复反馈 | SUBSCRIBE/REJECT | 同上（否定“不用”） | 同上 |
| a118 | dev | @助手 把这条的所有订阅者都通知一遍 | 回复反馈 | SUBSCRIBE/REJECT | 命中“订阅”（“订阅者”），把“群发通知”误判为订阅 | 同 a116 |
| a119 | dev | @助手 给所有点赞的人都订阅这条 | 回复反馈 | SUBSCRIBE/REJECT | 命中“订阅”，不识别“给所有人” | 同 a115 |
| a120 | dev | @助手 我不想蹲这条，别通知我 | 回复反馈 | SUBSCRIBE/REJECT | 命中“蹲”，不识别否定“不想/别” | 同 a116 |

可以归为三类根因：
- **目标不是反馈（a110–a113）：** Java 侧该补一道兜底校验，解析器侧该从上下文拿到父评论类型。这一类即使解析器修好了，Java 也应该拦住。
- **否定或取消（a116、a117、a120）：** 解析器需要识别否定和取消。
- **代他人、群发、批量（a115、a118、a119）：** 解析器需要识别作用对象不是发起人本人。

### 5.2 端到端被掩盖的风险（本基线缺证据）

**抽奖路径上 Java 的拦截能力本轮没有被检验到。** 所有抽奖都在解析层因为缺截止时间变成了补问，没有一条命令到达 `GiveawayService.createFromAssistant`。所以抽奖类“非法执行 0”不代表安全。

r1 冒烟用的是旧标注、只跑开发集、时间用 epoch 写法，不能作为本基线的数字。但它显示，只要解析器能解析出截止时间，下面几类会被 Java 执行：
- 不支持的条件被静默丢弃（转发、分享、评论字数、关注时长、@好友）；
- 矛盾条件被执行：“至少满足3项：点赞、评论”里“至少”后面没有紧跟数字，被当成 ALL[点赞,评论]，Java 照常创建（r1 a199）；“没关注但必须关注”（r1 a202）；
- 不可商用素材发商用许可、“转售”被当作商用、“另一个帖子的素材”、“作者本人也参与”、“同一人多次评论”。

A11 一旦让解析器支持自然时间，这些会立刻冒出来。建议 A11 修完之后重跑本端到端。另外建议在 Java 侧补校验，至少两条：
- 商用许可须在素材 `license_commercial` 范围内；
- 规则树之外的原文条件不能被静默丢弃（可以要求解析器回传未识别的片段）。

### 5.3 误执行搜索（62 条，无状态变更）

解析器把所有没命中关键字的输入都兜底成 SEARCH。按类别：

| 类别 | 条数 |
|---|---|
| irrelevant | 14 |
| sub_paraphrase | 9 |
| search_missing | 8 |
| search_contradiction | 6 |
| unrecognized | 6 |
| search_unsupported_filter | 5 |
| search_overreach | 4 |
| irrelevant_injection | 3 |
| sub_standard | 2 |
| gw_overreach | 2 |
| sub_no_parent | 1 |
| sub_parent_not_feedback | 1 |
| irrelevant_trap | 1 |

这类不改状态，但会在公开评论区发出一条与请求无关的“未找到…/找到…”回复。其中 `search_overreach`（如“找已下架的”“别人收藏夹里的”）靠可见性过滤，没有泄露数据，但回复语义不对。

## 6. 主要错误类型

| # | 错误类型 | 证据（全量 281 条） | 典型样例 |
|---|---|---|---|
| 1 | 自然语言时间完全不支持 | 44/44 条 gold-EXECUTE 抽奖都没执行，GIVEAWAY 字段 0% | a121 “今晚 8 点…送最新版素材的下载资格” → “请给出明确截止时间” |
| 2 | 没命中关键字就兜底为 SEARCH | UNKNOWN 召回 开发 4.3% / 测试 10%；13 条 SUBSCRIBE→SEARCH；误执行搜索 62 | a213 你好、a087 坐等这条改好、a095 关注这条反馈的后续 |
| 3 | 关键字匹配不看语义：否定、作用对象、陷阱词 | 端到端非法 10 条中 6 条；`抽象/抽帧/抽空` → GIVEAWAY，`改进/订阅了` → SUBSCRIBE | a116 取消蹲这条、a233 这画风好抽象、a239 我订阅了你的频道 |
| 4 | 拿不到上下文（父评论类型、本帖许可、参照时间） | a110–a113 非法订阅；许可对照组 a278/a279 只因时间解析失败才没执行 | §5.1 |
| 5 | 不支持的条件不上报 | `unsupportedTools` 0/10，已有 pytest 也是红的 | a188 点赞且转发 |
| 6 | 完全未提奖品时不补问，一律默认 DOWNLOAD（“送素材/送最新版”取 DOWNLOAD 是对的，但“没提奖品”和“送福利”也被当成 DOWNLOAD） | 30 条 `gw_missing_prize` 全部只因缺截止时间而补问；补上时间后会按 DOWNLOAD 执行（本轮缺证据，按代码逻辑如此） | a154、a151 |
| 7 | 搜索 query 带填充词 | queryClean 开发 13.9% / 测试 20%。Java 用整串 `LIKE '%q%'`，填充词或多个词会导致搜不到 | a016 “有没有那种竖着拍的下雨夜晚街道的视频啊” |
| 8 | 搜索中的矛盾或不支持的筛选不补问 | search_contradiction 0/6、search_unsupported_filter 0/5 | a064 找方形的竖屏街景 → 按竖屏执行 |
| 9 | 画幅写法覆盖不全 | aspectRatio 开发 80.6% | “竖着拍”“9:16”“16:9”“1:1”“竖瓶/横平” |
| 10 | 中文数字人数 | 本轮被错误类型 1 掩盖，缺证据 | “抽三位”“抽两位” |

## 7. 改进方向（供 A11 排序参考）

1. **Java 兜底（不依赖解析器）：**
   - 订阅时校验目标评论是反馈（有版本），且不是助手账号的评论；
   - 抽奖时校验商用许可在素材可发放范围内；
   - `unsupportedTools` 的流程保持“未开放”。
   这样能直接消掉 §5.1 的 a110–a113，并为 §5.2 兜底。
2. **补全契约：**
   - Java 在 `context` 里传入：`nowMs`/时区、父评论类型（feedback/ordinary/assistant）、本帖 `licenseCommercial`、已启用工具列表；
   - 解析器回传未识别的条件片段。
3. **解析器支持自然时间：** 今晚/明晚/明早、周X/下周X、M月D日、HH:MM、中午/下午/晚上、N小时后，以及中文数字。这是解锁抽奖评测的前提。
4. **意图判定先过一层：** 否定/取消、作用对象（帮他人/所有人）、陷阱词消歧；没命中关键字时回 UNKNOWN 补问，不再兜底为 SEARCH。
5. **抽奖：**
   - 完全没提奖品或说法含糊（如“送福利”）时补问；“送素材/送最新版”取 DOWNLOAD（默认 READY 版本），明确写“商用”才是 COMMERCIAL；
   - 填 `unsupportedTools`（转发、分享、打赏、等级、时长、弹幕、@好友、下载记录…）；
   - 校验矛盾（k>n、人数 ≤0 或 >100、窗口晚于截止、截止已过）；
   - 拒绝越权（指定中奖人、批量发放、他帖奖励、违反排除或去重）。
6. **搜索：** query 只保留实义词（或拆成多个关键词，Java 改成逐词 AND）；补全画幅同义写法；矛盾或不支持的筛选时补问。
7. **评测本身：**
   - 补问只判了“有没有补问”，没判“问得对不对”。下一版建议给 CLARIFY 行标上期望的补问原因（缺时间/缺奖品/矛盾/不支持…）并评分，否则 §4 那种“对在错误原因上”的情况看不出来；
   - A11 每修一项都在开发集上迭代，测试集只在出报告时跑。

## 8. 复现

```powershell
cd <worktree>\assistant-python
$PY = "E:\content\assistant-python\.venv\Scripts\python.exe"   # worktree 内没有 venv，复用主目录的
& $PY eval\run_eval.py --split dev  --out ..\target\assistant-eval\<新目录>\dev
& $PY eval\run_eval.py --split test --out ..\target\assistant-eval\<新目录>\test
& $PY eval\run_eval.py --split all --emit-e2e --out ..\target\assistant-eval\<新目录>\all
cd ..
$env:JAVA_HOME = "<JDK 17 路径，见 CLAUDE.md>"
E:\content\tools\apache-maven-3.9.6\bin\mvn -o -Pintegration "-Dtest=NoSuchTest" "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dit.test=AssistantEvalE2EIT" "-Dassistant.eval.cases=<abs>\all\e2e-cases.jsonl" "-Dassistant.eval.out=<abs>\all\e2e-results.jsonl" verify
& $PY assistant-python\eval\e2e_summary.py target\assistant-eval\<新目录>\all   # 非法执行 > 0 时退出码为 1
```
