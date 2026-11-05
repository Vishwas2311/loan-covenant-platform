"""GAP-05: tokens and cost of model calls are recorded per request and summarised for support."""

from __future__ import annotations

from pathlib import Path

import pytest
from conftest import h
from fastapi.testclient import TestClient
from test_api import upload

from dps import usage
from dps.advisor import Advisor
from dps.main import create_app
from dps.models import AiUsage

GOLDEN = Path(__file__).resolve().parents[2] / "golden-set" / "documents" / "AGR-003.pdf"


def metered(messages):
    """A model that reports 1,200 prompt and 80 completion tokens for every call."""
    usage.note("azure/test-model", 1200, 80)
    return "The DSCR floor is 1.50 times [p. 4]."


@pytest.fixture
def ai_client(settings, checker):
    settings.ai_prompt_cost_per_1k = 0.005
    settings.ai_completion_cost_per_1k = 0.015
    return TestClient(create_app(settings, cp_checker=checker, create_schema=True, advisor=Advisor(metered)))


def test_each_ai_answer_records_its_tokens_and_cost(ai_client):
    doc = upload(ai_client, data=GOLDEN.read_bytes(), name="AGR-003.pdf").json()["documentId"]
    assert ai_client.post(f"/dps/v1/documents/{doc}/ask", json={"question": "What is the minimum DSCR?"},
                          headers=h()).status_code == 200

    with ai_client.app.state.db.session_factory() as db:
        [row] = db.query(AiUsage).all()
    assert (row.purpose, row.user, row.document_id, row.model) == ("AGREEMENT_QA", "rm-anil", doc, "azure/test-model")
    assert (row.calls, row.prompt_tokens, row.completion_tokens) == (1, 1200, 80)
    assert row.cost == pytest.approx(1.2 * 0.005 + 0.08 * 0.015)

    summary = ai_client.get("/dps/v1/ai-usage?days=7", headers=h("support-1", "SUPPORT")).json()
    assert summary["promptTokens"] == 1200 and summary["completionTokens"] == 80
    assert summary["byPurpose"][0]["purpose"] == "AGREEMENT_QA" and summary["cost"] == pytest.approx(0.0072)
    assert ai_client.get("/dps/v1/ai-usage", headers=h()).status_code == 403  # an RM does not see spend


def test_suggestions_are_recorded_against_the_cp(ai_client):
    r = ai_client.post("/dps/v1/ai-suggestions", json={"cpId": "CP-1", "covenants": [
        {"appliedCovenantId": "ac-1", "covenantName": "DSCR", "latestResult": "BREACHED"}]},
        headers=h("core", "SERVICE"))
    assert r.status_code == 200
    with ai_client.app.state.db.session_factory() as db:
        [row] = db.query(AiUsage).all()
    assert (row.purpose, row.cp_id) == ("SUGGESTIONS", "CP-1")


def test_rules_answers_record_nothing_and_cost_needs_rates(client, settings):
    doc = upload(client, data=GOLDEN.read_bytes(), name="AGR-003.pdf").json()["documentId"]
    client.post(f"/dps/v1/documents/{doc}/ask", json={"question": "What is the minimum DSCR?"}, headers=h())
    with client.app.state.db.session_factory() as db:
        assert db.query(AiUsage).count() == 0
    assert client.get("/dps/v1/ai-usage", headers=h("admin-1", "ADMIN")).json()["cost"] is None

    usage.note("m", 10, 5)
    usage.note("m", 10, 5)
    tokens = usage.take()
    assert (tokens.calls, tokens.prompt, tokens.completion) == (2, 20, 10) and usage.take() is None
    assert usage.cost(settings, tokens) is None
