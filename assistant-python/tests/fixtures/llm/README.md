# Recorded LLM responses

Raw response bodies replayed by `tests/test_llm_parser.py` through a mock HTTP transport (no network in tests).

| dir | provider | provenance |
|---|---|---|
| `openai_compatible/` | DeepSeek `deepseek-flash` (https://api.deepseek.com) | **recorded** 2026-11-21 with `eval/record_llm_fixtures.py` (real API responses; re-recorded after `droppedRequirements` was added) |
| `anthropic/` | `claude-haiku-5-5` | **synthetic**: hand-written in the Messages API response shape (the `dropped_*` texts reuse the DeepSeek model output), because no Anthropic key was available. Re-record with `CONTENT_ASSISTANT_LLM_PROVIDER=anthropic` once one is |

Only response bodies are stored: no request, no key, no headers.
