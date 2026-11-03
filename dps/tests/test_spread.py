"""C6: the full spread of a financial statement, and reviewer corrections that Core then uses."""

from __future__ import annotations

from datetime import date

from conftest import h
from sqlalchemy import select
from test_api import add_value, session, upload

from dps.extraction.statement import SECTIONS, extract_values
from dps.models import EventOutbox, StatementValue


def statement(client) -> str:
    return upload(client, type_="FINANCIAL_STATEMENT", name="q3.csv", data=b"EBITDA,640",
                  as_of="2026-09-30").json()["documentId"]


def test_the_reader_maps_the_wider_spread():
    rows = [("Revenue from operations", 4200, None, "B2"), ("Other income", 35, None, "B3"),
            ("Employee benefits expense", 410, None, "B5"), ("Profit before tax", 260, None, "B9"),
            ("Property, plant and equipment", 1900, None, "B20"), ("Total equity and liabilities", 5100, None, "B40"),
            ("Net cash from operating activities", 520, None, "B50"), ("Dividends paid", 40, None, "B55")]
    keys = {v.key for v in extract_values(rows, "Amounts in INR crore").values}
    assert {"REVENUE", "OTHER_INCOME", "EMPLOYEE_COST", "PBT", "FIXED_ASSETS", "TOTAL_EQUITY_AND_LIABILITIES",
            "CFO", "DIVIDENDS_PAID"} <= keys


def test_spread_shows_every_line_with_its_status(client, app):
    doc = statement(client)
    add_value(app, "CP-1", "EBITDA", 640.0, date(2026, 9, 30), doc_id=doc)
    spread = client.get(f"/dps/v1/documents/{doc}/spread", headers=h()).json()
    assert len(spread) == sum(len(k) for k in SECTIONS.values())
    by_key = {line["standardKey"]: line for line in spread}
    assert by_key["EBITDA"]["status"] == "MAPPED" and by_key["EBITDA"]["value"] == 640.0
    assert by_key["CFO"]["status"] == "UNMAPPED" and by_key["CFO"]["value"] is None
    assert by_key["CFO"]["section"] == "CASH_FLOW"


def test_a_correction_is_served_to_core_and_announced(client, app):
    doc = statement(client)
    add_value(app, "CP-1", "EBITDA", 64.0, date(2026, 9, 30), doc_id=doc)
    r = client.put(f"/dps/v1/documents/{doc}/spread", headers=h(),
                   json={"lines": [{"standardKey": "EBITDA", "value": 640.0}, {"standardKey": "CFO", "value": 520.0}]})
    assert r.status_code == 200
    by_key = {line["standardKey"]: line for line in r.json()}
    assert by_key["EBITDA"]["status"] == "CONFIRMED" and by_key["EBITDA"]["value"] == 640.0
    assert by_key["CFO"]["value"] == 520.0
    pit = client.post("/dps/v1/values", json={"cpId": "CP-1", "to": "2026-09-30", "keys": ["EBITDA", "CFO"]},
                      headers=h()).json()
    assert pit["points"][0]["values"] == {"EBITDA": 640.0, "CFO": 520.0}
    with session(app) as s:
        assert len(list(s.scalars(select(EventOutbox)))) == 1
        old = s.scalars(select(StatementValue).where(StatementValue.value == 64.0)).one()
        assert old.superseded


def test_corrections_are_checked(client):
    doc = statement(client)
    assert client.put(f"/dps/v1/documents/{doc}/spread", headers=h(),
                      json={"lines": [{"standardKey": "NOPE", "value": 1}]}).json()["code"] == "UNKNOWN_LINE"
    assert client.put(f"/dps/v1/documents/{doc}/spread", headers=h("auditor-1", "AUDITOR"),
                      json={"lines": [{"standardKey": "EBITDA", "value": 1}]}).status_code == 403
    agreement = upload(client).json()["documentId"]
    assert client.get(f"/dps/v1/documents/{agreement}/spread", headers=h()).json()["code"] == "NOT_A_STATEMENT"
