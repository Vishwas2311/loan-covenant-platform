from __future__ import annotations

import io
import json
from datetime import date, timedelta
from pathlib import Path

import httpx
import pytest
from openpyxl import Workbook
from sqlalchemy import select

from dps import jobs
from dps.core_client import CoreClient, CoreMaster, CoreUnavailable
from dps.db import Base, Database
from dps.flows.intake import AgreementIntakeFlow, IntakeDeps
from dps.flows.persistence import SqlFlowPersistence
from dps.flows.statement import StatementDeps, StatementProcessingFlow
from dps.models import Document, DocumentCpLink, EventOutbox, ExtractedTerm, FlowState, Job, StatementValue, utcnow
from dps.parsing import Page
from dps.settings import Settings
from dps.worker import Worker, _kickoff, build_runners

GOLDEN = Path(__file__).resolve().parents[2] / "golden-set" / "documents"


@pytest.fixture
def database(tmp_path: Path) -> Database:
    db = Database(f"sqlite:///{tmp_path / 'flows.db'}")
    Base.metadata.create_all(db.engine)
    return db


def add_document(database: Database, type_: str = "LOAN_AGREEMENT", name: str = "a.pdf",
                 as_of: date | None = None, cps: tuple[str, ...] = ("CP-1",)) -> str:
    with database.session_factory() as s:
        d = Document(type=type_, file_name=name, storage_path="x", sha256=name, size_bytes=1, uploaded_by="u",
                     as_of_date=as_of)
        s.add(d)
        s.flush()
        for cp in cps:
            s.add(DocumentCpLink(document_id=d.id, cp_id=cp, linked_by="u"))
        s.commit()
        return d.id


class FakeCore:
    def __init__(self, masters=None, down: bool = False) -> None:
        self.masters = masters if masters is not None else [CoreMaster("m1", "Minimum DSCR", "FINANCIAL", "AUTO",
                                                                         ("DSCR",))]
        self.down = down
        self.events: list[dict] = []

    def active_masters(self):
        if self.down:
            raise CoreUnavailable("GET /api/v1/covenants: ConnectError")
        return self.masters

    def validate_formula(self, expression: str) -> list[str]:
        return []

    def send_data_event(self, payload: dict) -> dict:
        self.events.append(payload)
        return {"assessmentsQueued": 1, "duplicate": False}


class FakeParser:
    def __init__(self, pages: list[Page]) -> None:
        self.pages = pages

    def parse(self, data: bytes) -> list[Page]:
        return self.pages


AGREEMENT_PAGES = [
    Page(1, "4.1 Debt Service Coverage Ratio\nThe Borrower shall maintain a DSCR of not less than 1.25 times.\n"
            "4.2 Interest Coverage Ratio\nThe Borrower shall maintain an ICR of not less than 2.00 times.\n",
         "TEXT", 0.9),
]


def intake(database: Database, pages: list[Page], core: FakeCore | None = None, heavy: bool = False,
           min_conf: float = 0.8) -> AgreementIntakeFlow:
    deps = IntakeDeps(database.session_factory, lambda d: b"pdf", FakeParser(pages), core or FakeCore(), min_conf,
                      heavy)
    return AgreementIntakeFlow(deps)


def terms(database: Database, doc_id: str) -> dict[str, ExtractedTerm]:
    with database.session_factory() as s:
        return {t.metric_raw: t for t in s.scalars(select(ExtractedTerm).where(ExtractedTerm.document_id == doc_id))}


def doc_status(database: Database, doc_id: str) -> str:
    with database.session_factory() as s:
        return s.get(Document, doc_id).status


def test_flow_a_publishes_clean_agreement(database):
    doc_id = add_document(database)
    flow = intake(database, AGREEMENT_PAGES)
    flow.kickoff(inputs={"job_id": "j1", "document_id": doc_id})

    assert flow.state.outcome == "PUBLISHED"
    stored = terms(database, doc_id)
    assert stored["DSCR"].covenant_master_id == "m1" and stored["DSCR"].expression == "DSCR >= 1.25"
    assert stored["ICR"].expression == "DPS_EBITDA / DPS_INTEREST_PAID >= 2"
    assert {t.status for t in stored.values()} == {"PUBLISHED"}
    assert doc_status(database, doc_id) == "PUBLISHED"
    with database.session_factory() as s:
        steps = [r.method_name for r in s.scalars(select(FlowState).order_by(FlowState.saved_at))]
    assert steps == ["parse", "extract", "normalize", "validate", "publish"]


def test_flow_a_holds_for_review_then_finishes_after_decisions(database):
    doc_id = add_document(database)
    flow = intake(database, AGREEMENT_PAGES, min_conf=0.99)
    flow.kickoff(inputs={"job_id": "j1", "document_id": doc_id})
    assert flow.state.outcome == "NEEDS_REVIEW"
    assert doc_status(database, doc_id) == "NEEDS_REVIEW"
    assert json.loads(terms(database, doc_id)["DSCR"].issues_json) == ["LOW_CONFIDENCE"]

    again = intake(database, AGREEMENT_PAGES)
    again.kickoff(inputs={"id": flow.state.id, "job_id": "j1", "document_id": doc_id})
    assert again.state.outcome == "NEEDS_REVIEW"  # nobody decided yet

    with database.session_factory() as s:
        for t in s.scalars(select(ExtractedTerm).where(ExtractedTerm.document_id == doc_id)):
            t.status, t.reviewed_by = "PUBLISHED", "approver-1"
        s.commit()
    done = intake(database, AGREEMENT_PAGES)
    done.kickoff(inputs={"id": flow.state.id, "job_id": "j1", "document_id": doc_id})
    assert done.state.outcome == "PUBLISHED"
    assert doc_status(database, doc_id) == "PUBLISHED"


def test_flow_a_rerun_keeps_reviewed_terms(database):
    doc_id = add_document(database)
    intake(database, AGREEMENT_PAGES).kickoff(inputs={"job_id": "j1", "document_id": doc_id})
    with database.session_factory() as s:
        t = s.scalars(select(ExtractedTerm).where(ExtractedTerm.metric_raw == "DSCR")).one()
        t.expression, t.reviewed_by, t.method = "DSCR >= 1.3", "approver-1", "MANUAL"
        s.commit()
    intake(database, AGREEMENT_PAGES).kickoff(inputs={"job_id": "j2", "document_id": doc_id})
    stored = terms(database, doc_id)
    assert len(stored) == 2 and stored["DSCR"].expression == "DSCR >= 1.3"


def test_flow_a_fails_without_text_and_hands_scans_to_heavy_pool(database):
    doc_id = add_document(database)
    flow = intake(database, [Page(1, "", "NONE", 0.0)], heavy=True)
    flow.kickoff(inputs={"job_id": "j1", "document_id": doc_id})
    assert flow.state.outcome == "FAILED" and doc_status(database, doc_id) == "FAILED"

    scan = intake(database, [Page(1, "", "NONE", 0.0), Page(2, "x", "NONE", 0.1)])
    scan.kickoff(inputs={"job_id": "j1", "document_id": add_document(database, name="b.pdf")})
    assert scan.state.outcome == "HEAVY"


def test_flow_a_parse_error_fails(database):
    from dps.parsing import ParseError

    class Broken:
        def parse(self, data):
            raise ParseError("Unreadable PDF: PdfReadError")

    doc_id = add_document(database)
    deps = IntakeDeps(database.session_factory, lambda d: b"x", Broken(), FakeCore(), 0.8, False)
    flow = AgreementIntakeFlow(deps)
    flow.kickoff(inputs={"job_id": "j1", "document_id": doc_id})
    assert flow.state.outcome == "FAILED" and flow.state.error == "Unreadable PDF: PdfReadError"


def test_persistence_returns_latest_state(database):
    p = SqlFlowPersistence(database.session_factory)
    p.init_db()
    assert p.load_state("nope") is None
    p.save_state("u1", "a", {"n": 1})
    p.save_state("u1", "b", {"n": 2})
    assert p.load_state("u1") == {"n": 2}


# ---------- Flow B ----------

def xlsx_bytes(rows: list[tuple]) -> bytes:
    wb = Workbook()
    ws = wb.active
    ws.title = "BS"
    for r in rows:
        ws.append(list(r))
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def statement(database: Database, data: bytes, name: str, heavy: bool = True, pages=None, cps=("CP-1",),
              as_of=date(2026, 3, 31)):
    doc_id = add_document(database, "FINANCIAL_STATEMENT", name, as_of, cps)
    deps = StatementDeps(database.session_factory, lambda d: data, FakeParser(pages or []), "ds-dps", heavy)
    flow = StatementProcessingFlow(deps)
    flow.kickoff(inputs={"job_id": "j1", "document_id": doc_id})
    return flow, doc_id


def values(database: Database, cp: str = "CP-1", current: bool = True) -> dict[str, StatementValue]:
    with database.session_factory() as s:
        q = select(StatementValue).where(StatementValue.cp_id == cp)
        if current:
            q = q.where(StatementValue.superseded.is_(False))
        return {v.key: v for v in s.scalars(q)}


GOOD_ROWS = [("Amounts in INR lakhs", None), ("EBITDA", 500), ("Short-term borrowings", 300),
             ("Long-term borrowings", 700), ("Total equity", 2000), ("Cash and cash equivalents", 100)]


def test_flow_b_reports_xlsx_values_and_queues_c5(database):
    flow, doc_id = statement(database, xlsx_bytes(GOOD_ROWS), "fs.xlsx", cps=("CP-1", "CP-2"))
    assert flow.state.outcome == "REPORTED" and flow.state.unit_scale == "lakh"
    v = values(database)
    assert v["EBITDA"].value == 5.0 and v["EBITDA"].cell == "BS!B2"
    assert v["TOTAL_DEBT"].derived and v["TOTAL_DEBT"].value == 10.0
    assert v["NET_DEBT"].value == 9.0
    with database.session_factory() as s:
        events = [json.loads(e.payload_json) for e in s.scalars(select(EventOutbox))]
    assert {e["entityKey"] for e in events} == {"CP-1", "CP-2"}
    assert events[0]["documentId"] == doc_id and events[0]["asOfDate"] == "2026-03-31"


def test_flow_b_new_upload_supersedes_and_rerun_is_idempotent(database):
    statement(database, xlsx_bytes(GOOD_ROWS), "q1.xlsx")
    newer = [r if r[0] != "EBITDA" else ("EBITDA", 600) for r in GOOD_ROWS]
    flow, doc_id = statement(database, xlsx_bytes(newer), "q1-restated.xlsx")
    assert values(database)["EBITDA"].value == 6.0
    assert len([v for v in values(database, current=False).values()]) >= 1
    # Re-running the same document replaces its own rows instead of superseding them again.
    deps = StatementDeps(database.session_factory, lambda d: xlsx_bytes(newer), FakeParser([]), "ds-dps", True)
    StatementProcessingFlow(deps).kickoff(inputs={"job_id": "j2", "document_id": doc_id})
    with database.session_factory() as s:
        rows = s.scalars(select(StatementValue).where(StatementValue.key == "EBITDA")).all()
    assert sorted((r.value, r.superseded) for r in rows) == [(5.0, True), (6.0, False)]


def test_flow_b_csv_needs_review_then_reports_on_resume(database):
    csv_data = b"Line item,FY26,FY25\nEBITDA,120,100\nNet worth,900,800\n"
    flow, doc_id = statement(database, csv_data, "fs.csv")
    assert flow.state.outcome == "NEEDS_REVIEW"
    assert flow.state.issues == ["MISSING_LINE_ITEMS: TOTAL_DEBT"]
    assert doc_status(database, doc_id) == "NEEDS_REVIEW" and values(database) == {}

    deps = StatementDeps(database.session_factory, lambda d: csv_data, FakeParser([]), "ds-dps", True)
    resumed = StatementProcessingFlow(deps)
    resumed.kickoff(inputs={"id": flow.state.id, "job_id": "j1", "document_id": doc_id})
    assert resumed.state.outcome == "REPORTED" and values(database)["EBITDA"].value == 120


def test_flow_b_pdf_and_unsupported_types(database):
    pages = [Page(1, "Balance sheet (Rs. in crores)\nEBITDA ....... 50.5\nTotal debt 120\nNet worth 300\n",
                  "TEXT", 0.9)]
    flow, _ = statement(database, b"pdf", "fs.pdf", pages=pages)
    assert flow.state.outcome == "REPORTED" and values(database)["TOTAL_DEBT"].page == 1

    scan, _ = statement(database, b"pdf", "scan.pdf", heavy=False, pages=[Page(1, "", "NONE", 0.0)])
    assert scan.state.outcome == "HEAVY"

    xls, doc_id = statement(database, b"x", "old.xls")
    assert xls.state.outcome == "FAILED" and "not supported" in xls.state.error
    bad, _ = statement(database, b"not a workbook", "bad.xlsx")
    assert bad.state.outcome == "FAILED" and bad.state.error == "Unreadable statement: BadZipFile"
    empty, _ = statement(database, b"a,b\n", "empty.csv")
    assert empty.state.outcome == "FAILED" and empty.state.error == "No line items found in the statement"


# ---------- worker and queue ----------

def settings(tmp_path: Path, **kw) -> Settings:
    return Settings(database_url=f"sqlite:///{tmp_path / 'flows.db'}", storage_dir=tmp_path / "store",
                    core_data_source_id="ds-dps", worker_max_attempts=2, **kw)


def add_job(database: Database, doc_id: str, flow: str = "AGREEMENT_INTAKE", pool: str = "STANDARD") -> str:
    with database.session_factory() as s:
        j = Job(document_id=doc_id, flow=flow, pool=pool)
        s.add(j)
        s.commit()
        return j.id


def job(database: Database, job_id: str) -> Job:
    with database.session_factory() as s:
        return s.get(Job, job_id)


def test_worker_runs_flow_a_and_marks_job_done(database, tmp_path):
    doc_id = add_document(database)
    job_id = add_job(database, doc_id)

    def runner(j: Job):
        return _kickoff(intake(database, AGREEMENT_PAGES), j)

    w = Worker(settings(tmp_path), database.session_factory, FakeCore(), {"AGREEMENT_INTAKE": runner})
    assert w.run_once() is True
    j = job(database, job_id)
    assert j.status == jobs.DONE and j.flow_state_id and j.locked_by is None
    assert [s["step"] for s in json.loads(j.steps_json)] == ["START", "END"]
    assert w.run_once() is False


def test_worker_retries_transient_errors_then_fails(database, tmp_path):
    doc_id = add_document(database)
    job_id = add_job(database, doc_id)

    def runner(j: Job):
        return _kickoff(intake(database, AGREEMENT_PAGES, core=FakeCore(down=True)), j)

    w = Worker(settings(tmp_path), database.session_factory, FakeCore(), {"AGREEMENT_INTAKE": runner})
    w.run_once()
    assert job(database, job_id).status == jobs.QUEUED
    w.run_once()
    j = job(database, job_id)
    assert j.status == jobs.FAILED and "ConnectError" in j.error and doc_status(database, doc_id) == "FAILED"


def test_worker_fails_unknown_flow_and_crashing_runner(database, tmp_path):
    a = add_job(database, add_document(database), flow="NOPE")
    b = add_job(database, add_document(database, name="b.pdf"))

    def boom(j: Job):
        raise ValueError("bug")

    w = Worker(settings(tmp_path), database.session_factory, FakeCore(), {"AGREEMENT_INTAKE": boom})
    w.run_once()
    w.run_once()
    assert job(database, a).error == "No flow registered for NOPE"
    assert job(database, b).status == jobs.FAILED and job(database, b).error == "ValueError: bug"


def test_worker_moves_scans_to_heavy_pool(database, tmp_path):
    job_id = add_job(database, add_document(database))

    def runner(j: Job):
        return _kickoff(intake(database, [Page(1, "", "NONE", 0.0)]), j)

    w = Worker(settings(tmp_path), database.session_factory, FakeCore(), {"AGREEMENT_INTAKE": runner})
    w.run_once()
    j = job(database, job_id)
    assert (j.pool, j.status, j.attempts) == ("HEAVY", jobs.QUEUED, 0)
    assert w.run_once() is False  # a STANDARD worker does not take HEAVY jobs


def test_claim_takes_expired_leases_and_respects_attempts(database):
    doc_id = add_document(database)
    job_id = add_job(database, doc_id)
    with database.session_factory() as s:
        first = jobs.claim(s, "w1", "STANDARD", 60, 3)
        assert first.id == job_id and first.locked_by == "w1" and not jobs.lease_expired(first)
        assert jobs.claim(s, "w2", "STANDARD", 60, 3) is None
        first.locked_until = utcnow() - timedelta(seconds=1)
        s.commit()
        assert jobs.lease_expired(first)
        second = jobs.claim(s, "w2", "STANDARD", 60, 3)
        assert second.locked_by == "w2" and second.attempts == 2
        assert jobs.claim(s, "w3", "STANDARD", 60, 2) is None
    assert jobs.lease_expired(Job(document_id="x", flow="f"))


def test_outbox_sender(database, tmp_path):
    with database.session_factory() as s:
        for n in range(3):
            s.add(EventOutbox(type="DATA_EVENT", payload_json=json.dumps({"entityKey": f"CP-{n}"})))
        s.commit()

    class FlakyCore(FakeCore):
        def __init__(self):
            super().__init__()
            self.calls = 0

        def send_data_event(self, payload):
            self.calls += 1
            if payload["entityKey"] == "CP-1":
                request = httpx.Request("POST", "http://core/api/v1/data-events")
                raise httpx.HTTPStatusError("404", request=request, response=httpx.Response(404, text="no table",
                                                                                             request=request))
            if payload["entityKey"] == "CP-2" and self.calls == 3:
                raise CoreUnavailable("down")
            return super().send_data_event(payload)

    core = FlakyCore()
    w = Worker(settings(tmp_path), database.session_factory, core, {})
    assert w.send_events() == 1  # CP-0 sent, CP-1 permanently rejected, CP-2 hit an outage
    assert w.send_events() == 1  # CP-2 delivered on the next loop
    assert [e["dataSourceId"] for e in core.events] == ["ds-dps", "ds-dps"]
    with database.session_factory() as s:
        rejected = s.scalars(select(EventOutbox).where(EventOutbox.sent_at.is_(None))).one()
    assert rejected.last_error == "HTTP 404: no table" and rejected.attempts == 50
    assert w.run_once() is False


@pytest.mark.skipif(not GOLDEN.exists(), reason="golden set not present")
def test_build_runners_wires_real_parser(database, tmp_path):
    from dps.storage import FileStore

    store = FileStore(tmp_path / "store")
    _, rel = store.put((GOLDEN / "AGR-001.pdf").read_bytes(), ".pdf")
    doc_id = add_document(database)
    with database.session_factory() as s:
        s.get(Document, doc_id).storage_path = rel
        s.commit()
    runners = build_runners(settings(tmp_path, ocr_enabled=False), database, store, FakeCore())
    w = Worker(settings(tmp_path), database.session_factory, FakeCore(), runners)
    job_id = add_job(database, doc_id)
    w.run_once()
    assert job(database, job_id).status == jobs.DONE
    assert set(runners) == {"AGREEMENT_INTAKE", "STATEMENT_PROCESSING"}


# ---------- Core client ----------

def core_client(handler) -> CoreClient:
    return CoreClient("http://core", 1.0, "", client=httpx.Client(base_url="http://core",
                                                                   transport=httpx.MockTransport(handler)))


def test_core_client_pages_masters_and_uses_service_identity():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        page = int(request.url.params["page"])
        content = [{"id": f"m{page}", "name": f"N{page}", "category": "FINANCIAL", "assessmentType": "AUTO",
                    "referencedMeasures": ["DSCR"], "custom": False},
                   {"id": "c", "name": "custom", "custom": True}]
        return httpx.Response(200, json={"content": content, "last": page == 1})

    masters = core_client(handler).active_masters()
    assert [m.id for m in masters] == ["m0", "m1"] and masters[0].referenced_measures == ("DSCR",)
    assert seen[0].headers["X-Roles"] == "SERVICE"
    bearer = CoreClient("http://core", 1.0, "tok", client=httpx.Client(transport=httpx.MockTransport(handler)))
    assert bearer._headers == {"Authorization": "Bearer tok"}


def test_core_client_validate_and_events_and_errors():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/v1/formulas/validate":
            body = json.loads(request.content)
            if body["expression"] == "OK":
                return httpx.Response(200, json={"valid": True, "errors": []})
            if body["expression"] == "EMPTY":
                return httpx.Response(200, json={"valid": False, "errors": []})
            return httpx.Response(200, json={"valid": False, "errors": [{"code": "UNKNOWN_MEASURE"}]})
        if request.url.path == "/api/v1/data-events":
            return httpx.Response(202, json={"assessmentsQueued": 2, "duplicate": False})
        return httpx.Response(503)

    c = core_client(handler)
    assert c.validate_formula("OK") == []
    assert c.validate_formula("X") == ["UNKNOWN_MEASURE"]
    assert c.validate_formula("EMPTY") == ["invalid"]
    assert c.send_data_event({"a": 1}) == {"assessmentsQueued": 2, "duplicate": False}
    with pytest.raises(CoreUnavailable):
        c.active_masters()

    def offline(request):
        raise httpx.ConnectError("refused")

    with pytest.raises(CoreUnavailable):
        core_client(offline).validate_formula("OK")
