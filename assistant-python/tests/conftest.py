"""Regular tests never reach a real model.

Before any test module imports app.main: drop the API key from this process and force mode=rules, so the existing
endpoint tests keep exercising the rules parser even on a machine where the key is set. LLM tests build their own
ParserService on a mock HTTP transport with recorded responses (tests/fixtures/llm/).
"""
import os

os.environ.pop("CONTENT_ASSISTANT_LLM_API_KEY", None)
os.environ["CONTENT_ASSISTANT_PARSER_MODE"] = "rules"
