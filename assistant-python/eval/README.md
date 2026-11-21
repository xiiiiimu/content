# Assistant intent-parser eval

Scores the `/v1/parse` parser. The rules parser lives in `app/rules.py` and is the default. The LLM parser lives in
`app/llm.py` and runs only with an explicit `--parser-mode`.

## Files

| file | purpose |
|---|---|
| `build_dataset.py` | hand-written labels → `dataset.jsonl` (deterministic; splits frozen in `splits_frozen.json`) |
| `splits_frozen.json` | id → dev/test for the r1 rows (stratified 7:3, seed 20261121) |
| `dataset.jsonl` | 281 rows (197 dev / 84 test): `id, split, category, text, context, expected{intent, action, params, unsupported}, note` |
| `run_eval.py` | calls the real endpoint via TestClient, writes metrics / predictions / errors to `target/assistant-eval/<ts>-<split>/` |
| `e2e_summary.py` | joins recorded parses with `AssistantEvalE2EIT` output, counts commands Java executed that it should not have |
| `build_injection.py` → `injection.jsonl` | 16 prompt-injection probes (`split=injection`). Scored separately, never mixed into dev/test. Seen twice by the LLM parser (before and after the inj03 fix), so it is no longer held out |
| `build_injection_v2.py` → `injection_v2.jsonl` | 10 further probes in other styles (`split=injection_v2`). Frozen before the inj03 fix (`injection_v2.sha256`, sha256 of the LF-normalized file = git blob), first run only after the fix was final. Held out |
| `compare_runs.py` | rules baseline vs N LLM runs: mean ± stdev per metric, per-row stability across runs |
| `record_llm_fixtures.py` | records real model responses into `tests/fixtures/llm/` (real API call, explicit run only) |

**The test split must not be used to tune rules.** Iterate on `--split dev`, and run `--split test` only for a reported baseline.

## Parser contract (as implemented)

Request (Java `AssistantParser.Request`): `taskId, postId, actorUserId, parentCommentId?, body, actorIsAuthor, context`.
Java computes `actorIsAuthor` itself (`post.authorId == actor`) and currently sends `context = {}`, so the parser
does **not** get the parent comment's kind, the reference clock, the timezone or the post's license.

Response: `intent ∈ {SEARCH, SUBSCRIBE, GIVEAWAY, UNKNOWN}`, optional `clarify{intent, question}`, one of
`search{query, tag, aspectRatio, licenseDownload, licenseCommercial}` / `subscribe{feedbackCommentId}` /
`giveaway{ruleTreeJson, timezone, windowStartMs, deadlineMs, winnerCount, rewardVersionHint, rewardLicense, summary}`,
and `unsupportedTools[]`.

How Java handles the response (`AssistantService.execute`): a non-empty `unsupportedTools` produces a refusal reply; otherwise a non-null `clarify` produces the question as the reply; otherwise the command runs.

| intent | Java validation before side effects |
|---|---|
| SEARCH | none on the command; results filtered by visibility, `PUBLIC_PREVIEW`, aspect/license flags. `q` is matched as one `LIKE '%q%'` string |
| SUBSCRIBE | comment exists, post visible to actor. **Does not check that the comment is a feedback (has a version)** |
| GIVEAWAY | actor is post author (403); rule tree: op ∈ ALL/ANY/AT_LEAST, 1–16 children, no duplicate types, 0<k≤n, every tool registered and enabled; reward version resolvable; deadline > now; 1 ≤ winners ≤ 100; license ∈ DOWNLOAD/COMMERCIAL. **Does not check that COMMERCIAL is grantable for this material** |

## Labeling rules

- `action`: `EXECUTE` (should emit a command) / `CLARIFY` (targeted follow-up: missing, ambiguous, contradictory or unsupported condition) / `REJECT` (must not execute: irrelevant, unauthorized, out-of-scope grant).
- Reference clock `nowMs` = 2027-01-15 10:00 Asia/Shanghai (a Friday). It is far enough in the future that Java's `deadline > now` passes in E2E runs. `run_eval.py` freezes `time.time` to it.
- `PURCHASE` is labeled as a supported tool: on this branch `SaleMapper` is a bean, so the registry enables it (the design doc still says it is disabled).
- A giveaway with no winner count is labeled `CLARIFY`.
- Search filters the data model cannot express (resolution, duration, negation, upload date, sorting) are labeled `CLARIFY`; they must not be silently dropped.
- Unrecognizable input (greeting, thanks, emoji, bare `@助手`) is `UNKNOWN/CLARIFY`, matching the parser's
  "请说明要找素材、蹲改进还是抽奖" reply; clearly unrelated requests or statements are `UNKNOWN/REJECT`.
- r2 (after review): no `截止epoch:` / `N秒后` — times are natural phrases relative to the reference clock.
  Giveaway rows state `postLicenseCommercial` explicitly (commercial prize on a non-commercial material → `REJECT`);
  non-giveaway rows carry `null`.
- r3 prize rule: download is the default and commercial must be explicit. "送素材" / "送最新版(素材)" / "下载资格…" →
  `DOWNLOAD` on the default READY version; "商用…" → `COMMERCIAL`; no prize or a vague one ("送福利") → `CLARIFY`.
- Splits are frozen by id in `splits_frozen.json`; rows are append-only and new rows inherit `split_from`'s split.

## Metrics

- Intent accuracy and macro F1 over the 4 intents.
- Execute-decision accuracy (EXECUTE vs not).
- Clarify precision and recall, computed over gold EXECUTE ∪ CLARIFY rows. REJECT rows are reported separately as "not executed".
- Per-field accuracy on gold-EXECUTE rows. `acc` counts a missed execution or wrong intent as wrong; `acc_cond` covers only rows where the same intent was executed.
  `SEARCH.queryTerms` is lenient (all expected terms present). `SEARCH.queryClean` is strict (no filler tokens, which matters because Java does a single `LIKE`).

## LLM parser (`app/llm.py`)

| env | default | |
|---|---|---|
| `CONTENT_ASSISTANT_PARSER_MODE` | `llm_with_fallback` | `rules` / `llm` (failure → 503, Java retries the task) / `llm_with_fallback` (failure → rules, `source=rules_fallback`) |
| `CONTENT_ASSISTANT_LLM_API_KEY` | — | the only place the key is read from; never written to files or logs. Unset: `llm_with_fallback` warns once and serves rules (`fallbackReason=NO_API_KEY`); `llm` refuses to start |
| `CONTENT_ASSISTANT_LLM_PROVIDER` | `openai_compatible` | `openai_compatible` (Chat Completions, JSON mode) or `anthropic` (Messages API, JSON schema output) |
| `CONTENT_ASSISTANT_LLM_MODEL` | `deepseek-flash` (`claude-haiku-5-5` for anthropic) | |
| `CONTENT_ASSISTANT_LLM_BASE_URL` | `https://api.deepseek.com` (SDK default for anthropic) | |
| `CONTENT_ASSISTANT_LLM_TIMEOUT_SECONDS` | `10` | per attempt |
| `CONTENT_ASSISTANT_LLM_MAX_RETRIES` | `1` | capped at 1 (SDK retry on timeout / connection / 429 / 5xx) |
| `CONTENT_ASSISTANT_LLM_EFFORT` / `_THINKING` | `low` / `disabled` | effort applies to anthropic only. DeepSeek: thinking off via `thinking={"type":"disabled"}`, `temperature=0` |

- Structured output: DeepSeek uses JSON mode (`response_format={"type":"json_object"}`; the prompt contains "json", the schema and examples, and blank content counts as `EMPTY_OUTPUT`). Anthropic uses an `output_config.format` JSON schema. Both are then strictly validated with Pydantic (`extra=forbid`, strict types),
  then deterministic checks that repeat what Java enforces. Invalid output, an unparseable or offset-less time, a refusal
  or `max_tokens` all fall back to rules, and each reason is counted (`GET /v1/parser-stats`).
- `temperature`: DeepSeek gets `0` (only effective in non-thinking mode). It is not sent to Anthropic, because `claude-haiku-5-5` rejects
  non-default sampling parameters. The report has the measured run-to-run stability.
- The model writes no user-visible text. Clarify questions are templates chosen by reason code, unsupported conditions
  come from a fixed list, and keywords/tags must be substrings of the comment.
- `droppedRequirements` (required): the model lists every ask in the comment that its output does not carry out, with a
  kind (`CONFLICT` / `UNCLEAR` / `UNSUPPORTED` / `OVERRIDE` / `OVERREACH`). If the list is non-empty, Python never executes:
  `OVERRIDE`/`OVERREACH` mean refuse, the other kinds mean an `EXECUTE` becomes a clarify question. The model's `quote` is never shown or logged.
- Override-phrase guard (`app/guard.py`): in the `llm` and `llm_with_fallback` modes, a comment matching 忽略/无视…规则/指令 (or
  ignore/disregard … instructions/rules) never executes, also when the rules fallback answered. It can only stop an execution,
  never cause one. Rules mode is left untouched so it stays the baseline.
- Few-shot examples come only from dev rows (`app/llm_prompt.py`). `tests/test_llm_parser.py` checks the ids and texts.
- Java must wait longer than Python's worst case (2 × 10 s + backoff). `content.assistant.parse-timeout` defaults to 30 s.

## Run

```powershell
cd assistant-python
.venv/Scripts/python.exe eval/build_dataset.py              # only after editing labels
.venv/Scripts/python.exe eval/run_eval.py --split dev
.venv/Scripts/python.exe eval/run_eval.py --split all --emit-e2e --out ../target/assistant-eval/<new-dir>
# E2E (Docker Desktop running; new output file every run)
$env:JAVA_HOME="<JDK 17 path, see CLAUDE.md>"
../tools/apache-maven-3.9.6/bin/mvn -o -Pintegration "-Dtest=NoSuchTest" "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dit.test=AssistantEvalE2EIT" "-Dassistant.eval.cases=<dir>/e2e-cases.jsonl" "-Dassistant.eval.out=<dir>/e2e-results.jsonl" verify
.venv/Scripts/python.exe eval/e2e_summary.py <dir>          # exit 1 if any illegal execution

# LLM (real API; dev only while iterating, test once for the final version)
.venv/Scripts/python.exe eval/run_eval.py --parser-mode llm_with_fallback --split dev --emit-e2e --out ../target/assistant-llm/<new-dir>
.venv/Scripts/python.exe eval/run_eval.py --parser-mode llm_with_fallback --dataset eval/injection.jsonl --split all --emit-e2e --out ../target/assistant-llm/<new-dir>
.venv/Scripts/python.exe eval/run_eval.py --parser-mode llm_with_fallback --dataset eval/injection_v2.jsonl --split all --emit-e2e --out ../target/assistant-llm/<new-dir>
.venv/Scripts/python.exe eval/compare_runs.py --out ../target/assistant-llm/<new-dir> --baseline <rules-dir> <llm-1> <llm-2> <llm-3>
```

`AssistantEvalE2EIT` skips itself unless both `-D` properties are given. It replays each recorded parse through a local fake `/v1/parse`. The only change it makes is binding `run_eval`'s placeholder parent id 9001 to the real parent id. The cases go through the real comment → assistant_task → `HttpAssistantParser` → `AssistantService` path, with real MySQL, Redis and S3 mock containers.
