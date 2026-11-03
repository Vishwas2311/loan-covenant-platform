"""Phase 2: removing a wrong upload (B3) and linking an amendment to its agreement (C7)."""

from __future__ import annotations

from datetime import date

from conftest import h
from sqlalchemy import select
from test_api import PDF, add_term, add_value, session, upload

from dps.models import AuditEvent, Document, EventOutbox, ExtractedTerm, StatementValue


def published(app, doc_id: str) -> None:
    with session(app) as s:
        s.get(Document, doc_id).status = "PUBLISHED"
        s.commit()


def ebitda(client) -> float | None:
    pit = client.post("/dps/v1/values", json={"cpId": "CP-1", "to": "2026-09-30", "keys": ["EBITDA"]},
                      headers=h()).json()
    return pit["points"][0]["values"]["EBITDA"] if pit["points"] else None


def test_remove_withdraws_values_and_serves_the_earlier_one_again(client, app):
    old = upload(client, type_="FINANCIAL_STATEMENT", name="q3.csv", data=b"EBITDA,600", as_of="2026-09-30")
    new = upload(client, type_="FINANCIAL_STATEMENT", name="q3-wrong.csv", data=b"EBITDA,9", as_of="2026-09-30")
    old_id, new_id = old.json()["documentId"], new.json()["documentId"]
    published(app, old_id)
    published(app, new_id)
    add_value(app, "CP-1", "EBITDA", 600.0, date(2026, 9, 30), superseded=True, doc_id=old_id)
    add_value(app, "CP-1", "EBITDA", 9.0, date(2026, 9, 30), doc_id=new_id)
    assert ebitda(client) == 9.0

    r = client.post(f"/dps/v1/documents/{new_id}/remove", json={"reason": "wrong quarter"}, headers=h())
    assert r.status_code == 200 and r.json()["status"] == "REMOVED"
    assert ebitda(client) == 600.0
    with session(app) as s:
        events = [e.payload_json for e in s.scalars(select(EventOutbox))]
        assert len(events) == 1 and '"entityKey": "CP-1"' in events[0] and new_id in events[0]
        audit = s.scalars(select(AuditEvent).where(AuditEvent.action == "REMOVE")).one()
        assert "wrong quarter" in audit.detail_json

    again = client.post(f"/dps/v1/documents/{new_id}/remove", json={"reason": "x"}, headers=h())
    assert again.json()["code"] == "ALREADY_REMOVED"


def test_remove_withdraws_proposed_terms_and_blocks_while_processing(client, app):
    doc_id = upload(client).json()["documentId"]
    queued = client.post(f"/dps/v1/documents/{doc_id}/remove", json={"reason": "x"}, headers=h())
    assert queued.status_code == 409 and queued.json()["code"] == "STILL_PROCESSING"
    published(app, doc_id)
    add_term(app, doc_id, status="PUBLISHED")
    add_term(app, doc_id, metric_raw="LEV", status="REJECTED")
    assert client.post(f"/dps/v1/documents/{doc_id}/remove", json={"reason": "wrong loan"},
                       headers=h()).status_code == 200
    with session(app) as s:
        statuses = sorted(t.status for t in s.scalars(select(ExtractedTerm)))
        assert statuses == ["REJECTED", "WITHDRAWN"]
    assert client.post("/dps/v1/recommendations", json={"cpId": "CP-1"},
                       headers=h("core", "SERVICE")).json() == []


def test_remove_needs_a_writer_and_a_reason(client, app):
    doc_id = upload(client).json()["documentId"]
    published(app, doc_id)
    assert client.post(f"/dps/v1/documents/{doc_id}/remove", json={"reason": ""}, headers=h()).status_code == 400
    assert client.post(f"/dps/v1/documents/{doc_id}/remove", json={"reason": "x"},
                       headers=h("auditor-1", "AUDITOR")).status_code == 403
    assert client.post(f"/dps/v1/documents/{doc_id}/remove", json={"reason": "x"},
                       headers=h("rm-other", "RM")).status_code == 404


def test_same_file_after_removal_starts_afresh(client, app):
    first = upload(client).json()["documentId"]
    published(app, first)
    client.post(f"/dps/v1/documents/{first}/remove", json={"reason": "wrong loan"}, headers=h())
    second = upload(client).json()["documentId"]
    assert second != first


def test_amendment_links_to_its_agreement(client, app):
    original = upload(client).json()["documentId"]
    form = {"cpIds": ["CP-1"], "type": "LOAN_AGREEMENT", "amendsDocumentId": original}
    r = client.post("/dps/v1/documents", data=form,
                    files={"file": ("amendment.pdf", PDF + b"%amended", "application/pdf")}, headers=h())
    assert r.status_code == 202
    doc = client.get(f"/dps/v1/documents/{r.json()['documentId']}", headers=h()).json()
    assert doc["amendsDocumentId"] == original

    statement = upload(client, type_="FINANCIAL_STATEMENT", name="s.csv", data=b"EBITDA,1", as_of="2026-09-30")
    bad = client.post("/dps/v1/documents", headers=h(), data={
        "cpIds": ["CP-1"], "type": "LOAN_AGREEMENT", "amendsDocumentId": statement.json()["documentId"]},
        files={"file": ("a2.pdf", PDF + b"%2", "application/pdf")})
    assert bad.json()["code"] == "AMENDMENT_NOT_AGREEMENT"
    with session(app) as s:
        assert s.scalars(select(StatementValue)).first() is None
