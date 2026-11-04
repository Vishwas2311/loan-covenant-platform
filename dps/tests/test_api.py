from __future__ import annotations

from datetime import date, datetime, timedelta

import pytest
from conftest import h

from dps.models import Document, ExtractedTerm, Job, StatementValue

PDF = b"%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF"


def upload(client, *, cps=("CP-1",), type_="LOAN_AGREEMENT", data=PDF, name="agreement.pdf",
           as_of=None, headers=None):
    form = {"cpIds": list(cps), "type": type_}
    if as_of:
        form["asOfDate"] = as_of
    return client.post("/dps/v1/documents", data=form, files={"file": (name, data, "application/pdf")},
                       headers=headers or h())


def session(app):
    return app.state.db.session_factory()


# ---------- health / auth ----------

def test_health_is_public(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["database"] == "UP"
    assert "X-Correlation-Id" in r.headers


def test_correlation_id_echoed(client):
    r = client.get("/health", headers={"X-Correlation-Id": "abc"})
    assert r.headers["X-Correlation-Id"] == "abc"


def test_requires_identity(client):
    r = client.get("/dps/v1/catalog")
    assert r.status_code == 401
    assert r.json()["code"] == "UNAUTHORIZED"


def test_catalog(client):
    cols = client.get("/dps/v1/catalog", headers=h()).json()["columns"]
    assert {"key": "EBITDA", "label": "EBITDA", "dataType": "NUMBER", "kind": "METRIC"} in cols


# ---------- upload ----------

def test_upload_creates_document_link_and_job(client, app):
    r = upload(client, cps=("CP-1", "CP-2"))
    assert r.status_code == 202
    body = r.json()
    doc = client.get(f"/dps/v1/documents/{body['documentId']}", headers=h()).json()
    assert doc["cpIds"] == ["CP-1", "CP-2"]
    assert doc["status"] == "QUEUED"
    job = client.get(f"/dps/v1/jobs/{body['jobId']}", headers=h()).json()
    assert job["flow"] == "AGREEMENT_INTAKE" and job["status"] == "QUEUED" and job["pool"] == "STANDARD"


def test_upload_is_idempotent_and_adds_links(client):
    first = upload(client, cps=("CP-1",)).json()
    second = upload(client, cps=("CP-2",)).json()
    assert second == first
    doc = client.get(f"/dps/v1/documents/{first['documentId']}", headers=h()).json()
    assert doc["cpIds"] == ["CP-1", "CP-2"]


def test_upload_validation(client):
    assert upload(client, name="x.exe").json()["code"] == "UNSUPPORTED_FILE_TYPE"
    assert upload(client, data=b"").json()["code"] == "EMPTY_FILE"
    assert upload(client, data=b"x" * (1024 * 1024 + 1)).status_code == 413
    assert upload(client, type_="FINANCIAL_STATEMENT").json()["code"] == "AS_OF_DATE_REQUIRED"
    assert upload(client, cps=(" ",)).json()["code"] == "CP_REQUIRED"
    assert upload(client, type_="BOGUS").status_code == 400
    assert upload(client, name="fs.csv", data=b"Revenue,100").json()["code"] == "AGREEMENT_NOT_PDF"
    assert upload(client, name="fake.pdf", data=b"Revenue,100").json()["code"] == "NOT_A_PDF"


def test_upload_to_foreign_cp_is_hidden(client):
    r = upload(client, cps=("CP-9",))
    assert r.status_code == 404
    assert r.json()["code"] == "CP_NOT_FOUND"


def test_statement_and_evidence_upload(client):
    st = upload(client, type_="FINANCIAL_STATEMENT", name="fs.xlsx", data=b"PK\x03\x04data",
                as_of="2026-03-31").json()
    job = client.get(f"/dps/v1/jobs/{st['jobId']}", headers=h()).json()
    assert job["flow"] == "STATEMENT_PROCESSING"
    ev = upload(client, type_="EVIDENCE", name="cert.pdf", data=PDF + b"ev").json()
    assert ev["jobId"] is None
    assert client.get(f"/dps/v1/documents/{ev['documentId']}", headers=h()).json()["status"] == "PUBLISHED"


def test_list_documents_scoped(client):
    upload(client, cps=("CP-1",))
    assert len(client.get("/dps/v1/documents", params={"cpId": "CP-1"}, headers=h()).json()) == 1
    assert client.get("/dps/v1/documents", params={"cpId": "CP-1"},
                      headers=h("rm-other")).status_code == 404


def test_other_user_cannot_read_document_or_job(client):
    body = upload(client).json()
    assert client.get(f"/dps/v1/documents/{body['documentId']}", headers=h("rm-other")).status_code == 404
    assert client.get(f"/dps/v1/jobs/{body['jobId']}", headers=h("rm-other")).status_code == 404
    assert client.get("/dps/v1/documents/nope", headers=h()).status_code == 404
    assert client.get("/dps/v1/jobs/nope", headers=h()).status_code == 404


def test_auditor_can_read_but_not_write(client):
    body = upload(client).json()
    assert client.get(f"/dps/v1/documents/{body['documentId']}", headers=h("aud", "AUDITOR")).status_code == 200
    assert upload(client, cps=("CP-1",), data=PDF + b"2", headers=h("aud", "AUDITOR")).status_code == 404


# ---------- links ----------

def test_link_and_unlink(client):
    body = upload(client, cps=("CP-1",)).json()
    doc_id = body["documentId"]
    linked = client.post(f"/dps/v1/documents/{doc_id}/links", json={"cpIds": ["CP-2"]}, headers=h()).json()
    assert linked["cpIds"] == ["CP-1", "CP-2"]
    assert client.post(f"/dps/v1/documents/{doc_id}/links", json={"cpIds": ["CP-9"]},
                       headers=h()).status_code == 404
    unlinked = client.request("DELETE", f"/dps/v1/documents/{doc_id}/links", json={"cpIds": ["CP-2"]},
                              headers=h()).json()
    assert unlinked["cpIds"] == ["CP-1"]


# ---------- terms / recommendations (C1) ----------

def add_term(app, doc_id, **kw):
    with session(app) as s:
        t = ExtractedTerm(document_id=doc_id, metric_raw=kw.pop("metric_raw", "DSCR"), **kw)
        s.add(t)
        s.commit()
        return t.id


def test_recommendations_only_published_and_linked(client, app):
    doc_id = upload(client, cps=("CP-1",)).json()["documentId"]
    add_term(app, doc_id, covenant_master_id="c-1", expression="DSCR >= 1.25", status="PUBLISHED",
             confidence=0.9, page=14, quote="not less than 1.25x", frequency="QUARTERLY")
    add_term(app, doc_id, metric_raw="EBITDA_TO_EMI", expression="EBITDA / EMI_AMOUNT >= 1.20",
             status="PUBLISHED", confidence=0.8)
    add_term(app, doc_id, covenant_master_id="c-2", expression="X >= 1", status="NEEDS_REVIEW")
    recs = client.post("/dps/v1/recommendations", json={"cpId": "CP-1"}, headers=h()).json()
    assert len(recs) == 2
    assert recs[0]["covenantMasterId"] == "c-1"
    assert recs[0]["source"]["page"] == 14 and recs[0]["recommendedExpression"] == "DSCR >= 1.25"
    assert recs[1]["covenantMasterId"] is None
    assert recs[1]["customCovenant"]["assessmentType"] == "AUTO"
    assert client.post("/dps/v1/recommendations", json={"cpId": "CP-2"}, headers=h()).json() == []
    assert client.post("/dps/v1/recommendations", json={"cpId": "CP-1"},
                       headers=h("rm-other")).status_code == 404


def test_term_review_flow(client, app):
    doc_id = upload(client).json()["documentId"]
    tid = add_term(app, doc_id, expression="DSCR >= 1.2", status="NEEDS_REVIEW", issues_json='["quote not found"]')
    terms = client.get(f"/dps/v1/documents/{doc_id}/terms", headers=h()).json()
    assert terms[0]["issues"] == ["quote not found"]
    edited = client.patch(f"/dps/v1/terms/{tid}", json={"action": "EDIT", "expression": "DSCR >= 1.25",
                                                          "covenantMasterId": "c-1"}, headers=h()).json()
    assert edited["method"] == "MANUAL" and edited["status"] == "NEEDS_REVIEW"
    approved = client.patch(f"/dps/v1/terms/{tid}", json={"action": "APPROVE", "note": "checked p.14"},
                            headers=h()).json()
    assert approved["status"] == "PUBLISHED" and approved["reviewedBy"] == "rm-anil"
    rejected = client.patch(f"/dps/v1/terms/{tid}", json={"action": "REJECT"}, headers=h()).json()
    assert rejected["status"] == "REJECTED"
    assert client.patch("/dps/v1/terms/none", json={"action": "REJECT"}, headers=h()).status_code == 404
    assert client.patch(f"/dps/v1/terms/{tid}", json={"action": "REJECT"},
                        headers=h("rm-other")).status_code == 404


# ---------- jobs ----------

def test_resume_only_from_needs_review(client, app):
    body = upload(client).json()
    assert client.post(f"/dps/v1/jobs/{body['jobId']}/resume", headers=h()).status_code == 409
    from dps.models import Job
    with session(app) as s:
        s.get(Job, body["jobId"]).status = "NEEDS_REVIEW"
        s.commit()
    r = client.post(f"/dps/v1/jobs/{body['jobId']}/resume", headers=h())
    assert r.status_code == 202 and r.json()["status"] == "QUEUED"
    assert client.post("/dps/v1/jobs/none/resume", headers=h()).status_code == 404


# ---------- values (C2) ----------

def add_value(app, cp, key, value, as_of, superseded=False, doc_id=None):
    with session(app) as s:
        s.add(StatementValue(cp_id=cp, key=key, value=value, unit="AED mn", as_of_date=as_of,
                             source_type="DOCUMENT", superseded=superseded, document_id=doc_id, page=3))
        s.commit()


def test_values_point_in_time_and_window(client, app):
    add_value(app, "CP-1", "EBITDA", 200.0, date(2025, 12, 31))
    add_value(app, "CP-1", "EBITDA", 210.0, date(2026, 3, 31))
    add_value(app, "CP-1", "EBITDA", 999.0, date(2026, 3, 31), superseded=True)
    add_value(app, "CP-1", "INTEREST_PAID", 41.0, date(2026, 3, 31))
    add_value(app, "CP-1", "EBITDA", 230.0, date(2026, 6, 30))

    pit = client.post("/dps/v1/values", json={"cpId": "CP-1", "to": "2026-03-31",
                                              "keys": ["EBITDA", "INTEREST_PAID", "CASH"]}, headers=h()).json()
    assert pit["points"] == [{"asOfDate": "2026-03-31", "values": {"EBITDA": 210.0, "INTEREST_PAID": 41.0}}]
    assert pit["missingKeys"] == ["CASH"]
    # Core reads this as java.time.Instant, which needs an explicit offset (SQLite returns naive values).
    assert datetime.fromisoformat(pit["sourceChangedAt"]).utcoffset() == timedelta(0)

    win = client.post("/dps/v1/values", json={"cpId": "CP-1", "from": "2025-10-01", "to": "2026-03-31",
                                              "keys": ["EBITDA"]}, headers=h()).json()
    assert [p["asOfDate"] for p in win["points"]] == ["2025-12-31", "2026-03-31"]

    empty = client.post("/dps/v1/values", json={"cpId": "CP-2", "to": "2026-03-31", "keys": ["EBITDA"]},
                        headers=h()).json()
    assert empty == {"cpId": "CP-2", "points": [], "missingKeys": ["EBITDA"], "sourceChangedAt": None}


def test_values_service_role_and_scope(client, app):
    add_value(app, "CP-1", "EBITDA", 1.0, date(2026, 3, 31))
    assert client.post("/dps/v1/values", json={"cpId": "CP-1", "to": "2026-03-31", "keys": ["EBITDA"]},
                       headers=h("core-svc", "SERVICE")).status_code == 200
    assert client.post("/dps/v1/values", json={"cpId": "CP-1", "to": "2026-03-31", "keys": ["EBITDA"]},
                       headers=h("rm-other")).status_code == 404
    assert client.post("/dps/v1/values", json={"cpId": "CP-1", "keys": []}, headers=h()).status_code == 400


def test_document_values_listing(client, app):
    doc_id = upload(client, type_="FINANCIAL_STATEMENT", name="fs.pdf", data=PDF + b"fs",
                    as_of="2026-03-31").json()["documentId"]
    add_value(app, "CP-1", "EBITDA", 210.0, date(2026, 3, 31), doc_id=doc_id)
    vals = client.get(f"/dps/v1/documents/{doc_id}/values", headers=h()).json()
    assert vals[0]["source"] == {"documentId": doc_id, "page": 3, "cell": None}


# ---------- manual values (FR-20) ----------

def test_manual_value_requires_approver_and_supersedes(client, app):
    body = {"asOfDate": "2026-03-31", "key": "EBITDA", "value": 205.0, "unit": "AED mn", "note": "From MIS"}
    assert client.post("/dps/v1/cps/CP-1/manual-values", json=body, headers=h()).status_code == 403
    assert client.post("/dps/v1/cps/CP-2/manual-values", json=body,
                       headers=h("approver-1", "APPROVER")).status_code == 404
    add_value(app, "CP-1", "EBITDA", 210.0, date(2026, 3, 31))
    r = client.post("/dps/v1/cps/CP-1/manual-values", json=body, headers=h("approver-1", "APPROVER"))
    assert r.status_code == 201
    assert r.json()["sourceType"] == "USER" and r.json()["enteredBy"] == "approver-1"
    pit = client.post("/dps/v1/values", json={"cpId": "CP-1", "to": "2026-03-31", "keys": ["EBITDA"]},
                      headers=h()).json()
    assert pit["points"][0]["values"]["EBITDA"] == 205.0
    from sqlalchemy import select

    from dps.models import AuditEvent, EventOutbox
    with session(app) as s:
        events = list(s.scalars(select(EventOutbox)))
        assert len(events) == 1 and '"dataSourceId": "ds-dps"' in events[0].payload_json
        assert s.scalars(select(AuditEvent).where(AuditEvent.action == "MANUAL_VALUE")).first() is not None
    assert client.post("/dps/v1/cps/CP-1/manual-values", json={**body, "note": "x"},
                       headers=h("approver-1", "APPROVER")).status_code == 400


# ---------- AI suggestions (C3) ----------

def test_ai_suggestions_service_only(client):
    req = {"cpId": "CP-1", "covenants": []}
    assert client.post("/dps/v1/ai-suggestions", json=req, headers=h()).status_code == 403
    assert client.post("/dps/v1/ai-suggestions", json=req, headers=h("core", "SERVICE")).json() == []


def test_document_model_active_links(app):
    with session(app) as s:
        d = Document(type="LOAN_AGREEMENT", file_name="a.pdf", storage_path="x", sha256="0" * 64, size_bytes=1,
                     uploaded_by="u")
        s.add(d)
        s.commit()
        assert d.active_cp_ids() == []


def test_document_file_is_served_inline_to_people_with_access(client):
    doc_id = upload(client).json()["documentId"]
    r = client.get(f"/dps/v1/documents/{doc_id}/file", headers=h())
    assert r.status_code == 200 and r.content == PDF
    assert r.headers["content-type"] == "application/pdf"
    assert r.headers["content-disposition"].startswith("inline")
    assert client.get(f"/dps/v1/documents/{doc_id}/file", headers=h("rm-other")).status_code == 404


def test_document_file_missing_on_disk(client, app):
    doc_id = upload(client).json()["documentId"]
    with app.state.db.session_factory() as s:
        s.get(Document, doc_id).storage_path = "zz/gone.pdf"
        s.commit()
    assert client.get(f"/dps/v1/documents/{doc_id}/file", headers=h()).status_code == 404


def test_job_operations_for_support(client, app):
    doc_id = upload(client).json()["documentId"]
    support = h("support-1", "SUPPORT")
    assert client.get("/dps/v1/jobs", headers=h()).status_code == 403
    jobs = client.get("/dps/v1/jobs", headers=support).json()
    assert len(jobs) == 1 and jobs[0]["status"] == "QUEUED"
    job_id = jobs[0]["id"]
    assert client.post(f"/dps/v1/jobs/{job_id}/retry", headers=support).status_code == 409
    with app.state.db.session_factory() as s:
        j = s.get(Job, job_id)
        j.status, j.attempts, j.error = "FAILED", 3, "boom"
        s.get(Document, doc_id).status = "FAILED"
        s.commit()
    assert [j["id"] for j in client.get("/dps/v1/jobs?status=failed", headers=support).json()] == [job_id]
    assert client.get("/dps/v1/jobs?status=DONE", headers=support).json() == []
    retried = client.post(f"/dps/v1/jobs/{job_id}/retry", headers=support).json()
    assert (retried["status"], retried["attempts"], retried["error"]) == ("QUEUED", 0, None)
    assert client.post("/dps/v1/jobs/nope/retry", headers=support).status_code == 404
    assert client.post(f"/dps/v1/jobs/{job_id}/retry", headers=h()).status_code == 403


def test_support_and_auditor_read_but_never_change_a_cp(client, app):
    doc_id = upload(client).json()["documentId"]
    tid = add_term(app, doc_id, expression="DSCR >= 1.2", status="NEEDS_REVIEW")
    for who in (h("support-1", "SUPPORT"), h("aud-1", "AUDITOR")):
        assert client.get("/dps/v1/documents?cpId=CP-1", headers=who).status_code == 200
        assert client.get(f"/dps/v1/documents/{doc_id}/terms", headers=who).status_code == 200
        assert upload(client, headers=who).status_code == 404
        assert client.post(f"/dps/v1/documents/{doc_id}/links", json={"cpIds": ["CP-2"]},
                           headers=who).status_code == 404
        assert client.request("DELETE", f"/dps/v1/documents/{doc_id}/links", json={"cpIds": ["CP-1"]},
                              headers=who).status_code == 404
        assert client.patch(f"/dps/v1/terms/{tid}", json={"action": "APPROVE"}, headers=who).status_code == 404
        assert client.post(f"/dps/v1/documents/{doc_id}/remove", json={"reason": "x"}, headers=who).status_code == 403


def test_rate_limits_uploads_per_user(client, app):
    app.state.settings.uploads_per_minute = 2
    try:
        assert upload(client, name="a.pdf", data=PDF + b"1").status_code == 202
        assert upload(client, name="b.pdf", data=PDF + b"2").status_code == 202
        limited = upload(client, name="c.pdf", data=PDF + b"3")
        assert limited.status_code == 429 and limited.json()["code"] == "RATE_LIMITED"
        assert limited.json()["details"]["retryAfterSeconds"] >= 1
        assert upload(client, name="d.pdf", data=PDF + b"4", headers=h("wrm-meera", "APPROVER")).status_code != 429
    finally:
        app.state.settings.uploads_per_minute = 30


def test_rate_limiter_window_and_switch_off():
    from dps.errors import ApiError
    from dps.ratelimit import RateLimiter

    now = [0.0]
    lim = RateLimiter(clock=lambda: now[0])
    lim.check("u", "ask", 1)
    with pytest.raises(ApiError) as ex:
        lim.check("u", "ask", 1)
    assert ex.value.status == 429
    lim.check("u", "upload", 1)
    now[0] = 61.0
    lim.check("u", "ask", 1)
    for _ in range(100):
        lim.check("u", "ask", 0)
