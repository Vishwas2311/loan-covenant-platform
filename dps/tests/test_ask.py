from __future__ import annotations

import httpx
import pytest
from conftest import FakeChecker, h

from dps.ask import MAX_CONTEXT, REFUSAL, _trim, answer, core_fetch
from dps.auth import Principal
from dps.main import create_app

SUMMARY = [{"cpId": "CP-1", "applied": 3, "breached": 1, "error": 0},
           {"cpId": "CP-2", "applied": 2, "breached": 0, "error": 1}]
BREACHES = [{"cpId": "CP-1", "appliedCovenantId": "a1", "covenantName": "DSCR", "result": "BREACHED",
             "daysOpen": 40, "status": "BREACHED", "escalate": True}]


def fetch(principal, path):
    return SUMMARY if path.endswith("/summary") else BREACHES


ME = Principal("rm-anil", frozenset({"RM"}))


def test_refuses_off_topic_and_empty():
    assert answer("what's the weather", ME, fetch, None)["answer"] == REFUSAL
    assert answer("  ", ME, fetch, None)["citations"] == []


def test_rules_answers_cite_records():
    named = answer("How is CP-2 doing on covenants?", ME, fetch, None)
    assert named["citations"] == ["cp:CP-2"] and "1 with data errors" in named["answer"]
    open_ = answer("Which breaches should I escalate?", ME, fetch, None)
    assert open_["citations"] == ["breach:a1"] and "(escalate)" in open_["answer"]
    portfolio = answer("Summarise my portfolio", ME, fetch, None)
    assert portfolio["citations"] == ["cp:CP-1", "cp:CP-2"]
    empty = answer("any breaches?", ME, lambda p, path: [], None)
    assert empty["answer"].startswith("Nothing in your portfolio")


def test_model_answer_and_fallback():
    out = answer("Which covenants breached?", ME, fetch, lambda m: "CP-1 breached DSCR [breach:a1].")
    assert out["method"] == "AI" and out["citations"] == ["breach:a1"]

    def boom(messages):
        raise RuntimeError

    assert answer("Which covenants breached?", ME, fetch, boom)["method"] == "RULES"


def test_context_is_capped():
    big = [{"ref": f"cp:{i}", "cpId": str(i), "pad": "x" * 500} for i in range(100)]
    trimmed = _trim(big)
    assert 0 < len(trimmed) < 100
    assert sum(len(str(r)) for r in trimmed) <= MAX_CONTEXT * 1.2


def test_core_fetch_forwards_identity():
    seen = []

    def handler(request):
        seen.append(request.headers)
        return httpx.Response(200, json=[])

    f = core_fetch("http://core", 1.0, httpx.Client(base_url="http://core", transport=httpx.MockTransport(handler)))
    assert f(ME, "/api/v1/monitoring/summary") == []
    assert seen[0]["X-User"] == "rm-anil" and seen[0]["X-Roles"] == "RM"
    f(Principal("u", frozenset(), token="tok"), "/x")
    assert seen[1]["Authorization"] == "Bearer tok"


def test_ask_endpoint(settings):
    app = create_app(settings, cp_checker=FakeChecker({}), create_schema=True, core_fetch=fetch)
    from fastapi.testclient import TestClient

    client = TestClient(app)
    body = client.post("/dps/v1/ask", json={"question": "Which breaches are open?"}, headers=h()).json()
    assert body["citations"] == ["breach:a1"]
    assert client.post("/dps/v1/ask", json={"question": ""}, headers=h()).status_code in (400, 422)

@pytest.mark.parametrize("respond, status, code", [
    (lambda request: (_ for _ in ()).throw(httpx.ReadTimeout("slow")), 503, "CORE_UNAVAILABLE"),
    (lambda request: httpx.Response(502), 503, "CORE_UNAVAILABLE"),
    (lambda request: httpx.Response(401), 401, "CORE_REFUSED"),
])
def test_core_fetch_turns_core_failures_into_clear_errors(respond, status, code):
    from dps.errors import ApiError

    f = core_fetch("http://core", 1.0, httpx.Client(base_url="http://core", transport=httpx.MockTransport(respond)))
    with pytest.raises(ApiError) as err:
        f(Principal(user="rm-anil", roles=frozenset({"RM"})), "/api/v1/monitoring/summary")
    assert (err.value.status, err.value.code) == (status, code)

