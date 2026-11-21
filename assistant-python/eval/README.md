# Assistant intent-parser eval

Scores the **current rule-based parser** (`app/main.py`, `POST /v1/parse`) without changing it.

## Files

| file | purpose |
|---|---|
| `build_dataset.py` | hand-written labels → `dataset.jsonl` (deterministic; splits frozen in `splits_frozen.json`) |
| `splits_frozen.json` | id → dev/test for the r1 rows (stratified 7:3, seed 20261121) |
| `dataset.jsonl` | 281 rows (197 dev / 84 test): `id, split, category, text, context, expected{intent, action, params, unsupported}, note` |
| `run_eval.py` | calls the real endpoint via TestClient, writes metrics / predictions / errors to `target/assistant-eval/<ts>-<split>/` |
| `e2e_summary.py` | joins recorded parses with `AssistantEvalE2EIT` output, counts commands Java executed that it should not have |

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
```

`AssistantEvalE2EIT` skips itself unless both `-D` properties are given. It replays each recorded parse through a local fake `/v1/parse`. The only change it makes is binding `run_eval`'s placeholder parent id 9001 to the real parent id. The cases go through the real comment → assistant_task → `HttpAssistantParser` → `AssistantService` path, with real MySQL, Redis and S3 mock containers.
