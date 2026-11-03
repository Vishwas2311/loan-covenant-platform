"""T12 provider contract tests: real DPS responses must validate against contracts/dps-api.yaml (C1, C2, C3).

Core's consumer side is covered by its WebClient tests, which parse these same payload shapes.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path

import jsonschema
import pytest
import yaml
from conftest import h

from dps.models import Document, DocumentCpLink, ExtractedTerm, StatementValue

CONTRACT = Path(__file__).resolve().parents[2] / "contracts" / "dps-api.yaml"

pytestmark = pytest.mark.skipif(not CONTRACT.exists(), reason="contracts/ not present")


@pytest.fixture(scope="module")
def spec() -> dict:
    return yaml.safe_load(CONTRACT.read_text(encoding="utf-8"))


def response_schema(spec: dict, path: str, method: str = "post") -> dict:
    schema = spec["paths"][path][method]["responses"]["200"]["content"]["application/json"]["schema"]
    # Resolve "#/components/..." refs against the whole document (OpenAPI 3.1 = JSON Schema 2020-12).
    return {**schema, "components": spec["components"]}


def validate(spec: dict, path: str, body, method: str = "post") -> None:
    schema = response_schema(spec, path, method)
    jsonschema.validate(body, schema, cls=jsonschema.Draft202012Validator)


def seed(app) -> None:
    with app.state.db.session_factory() as s:
        agreement = Document(type="LOAN_AGREEMENT", file_name="a.pdf", storage_path="x", sha256="c1", size_bytes=1,
                             uploaded_by="u", status="PUBLISHED")
        statement = Document(type="FINANCIAL_STATEMENT", file_name="fs.xlsx", storage_path="y", sha256="c2",
                             size_bytes=1, uploaded_by="u", as_of_date=date(2026, 3, 31), status="PUBLISHED")
        s.add_all([agreement, statement])
        s.flush()
        for d in (agreement, statement):
            s.add(DocumentCpLink(document_id=d.id, cp_id="CP-1", linked_by="u"))
        s.add(ExtractedTerm(document_id=agreement.id, metric_raw="DSCR", covenant_master_id="m1",
                            expression="DSCR >= 1.25", operator=">=", threshold=1.25, frequency="QUARTERLY",
                            category="FINANCIAL", confidence=0.95, status="PUBLISHED", page=3, quote="not less"))
        s.add(ExtractedTerm(document_id=agreement.id, metric_raw="NEGATIVE_PLEDGE", category="NON_FINANCIAL",
                            confidence=0.8, status="PUBLISHED", page=4, quote="shall not"))
        s.add(StatementValue(cp_id="CP-1", key="EBITDA", value=50.0, unit="INR_CR", as_of_date=date(2026, 3, 31),
                             source_type="DOCUMENT", document_id=statement.id))
        s.commit()


def test_c1_recommendations_match_contract(spec, client, app):
    seed(app)
    body = client.post("/dps/v1/recommendations", json={"cpId": "CP-1"}, headers=h("core", "SERVICE")).json()
    assert len(body) == 2
    validate(spec, "/dps/v1/recommendations", body)


def test_c2_values_match_contract(spec, client, app):
    seed(app)
    req = {"cpId": "CP-1", "to": "2026-06-30", "keys": ["EBITDA", "NET_WORTH"]}
    body = client.post("/dps/v1/values", json=req, headers=h("core", "SERVICE")).json()
    assert body["missingKeys"] == ["NET_WORTH"]
    validate(spec, "/dps/v1/values", body)


def test_c3_ai_suggestions_match_contract(spec, client):
    req = {"cpId": "CP-1", "covenants": [
        {"appliedCovenantId": "a1", "latestResult": "COMPLIANT"},
        {"appliedCovenantId": "a2", "latestResult": "BREACHED", "computedValue": "1.1",
         "conditionExpression": "DSCR >= 1.25"}]}
    body = client.post("/dps/v1/ai-suggestions", json=req, headers=h("core", "SERVICE")).json()
    assert len(body) == 2
    validate(spec, "/dps/v1/ai-suggestions", body)


def test_contract_rejects_a_wrong_shape(spec):
    with pytest.raises(jsonschema.ValidationError):
        validate(spec, "/dps/v1/values", {"cpId": "CP-1", "points": [{"values": {}}]})
