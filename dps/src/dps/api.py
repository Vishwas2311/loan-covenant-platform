"""HTTP routes (contract: contracts/dps-api.yaml). Sync handlers run in FastAPI's threadpool."""

from __future__ import annotations

import json
from datetime import date
from pathlib import Path
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, File, Form, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field
from sqlalchemy import false, select, text
from sqlalchemy.orm import Session

from dps import __version__, services
from dps.access import CpAccessChecker, require_cp
from dps.auth import ADMIN, APPROVER, SERVICE, SUPPORT, Principal, require_roles
from dps.catalog import catalog as line_item_catalog
from dps.errors import ApiError, not_found
from dps.models import Document, DocumentCpLink, ExtractedTerm, Job, StatementValue, utcnow
from dps.settings import Settings
from dps.storage import FileStore

router = APIRouter()

ALLOWED_EXTENSIONS = {".pdf", ".xlsx", ".xls", ".csv"}
FLOW_BY_TYPE = {"LOAN_AGREEMENT": "AGREEMENT_INTAKE", "FINANCIAL_STATEMENT": "STATEMENT_PROCESSING"}


# ---------- dependencies ----------

def get_db(request: Request):
    yield from request.app.state.db.session()


def get_principal(request: Request) -> Principal:
    return request.app.state.authenticator.authenticate(request)


def get_checker(request: Request) -> CpAccessChecker:
    return request.app.state.cp_checker


def get_settings_dep(request: Request) -> Settings:
    return request.app.state.settings


def get_store(request: Request) -> FileStore:
    return request.app.state.store


DB = Annotated[Session, Depends(get_db)]
User = Annotated[Principal, Depends(get_principal)]
Checker = Annotated[CpAccessChecker, Depends(get_checker)]


# ---------- schemas ----------

class RecommendationRequest(BaseModel):
    cpId: str = Field(min_length=1)


class ValuesRequest(BaseModel):
    cpId: str = Field(min_length=1)
    to: date
    keys: list[str]
    from_: date | None = Field(default=None, alias="from")


class LinkRequest(BaseModel):
    cpIds: list[str] = Field(min_length=1)


class TermReview(BaseModel):
    action: Literal["APPROVE", "REJECT", "EDIT"]
    expression: str | None = None
    covenantMasterId: str | None = None
    note: str | None = Field(default=None, max_length=2000)


class ManualValue(BaseModel):
    asOfDate: date
    key: str = Field(min_length=1, max_length=64)
    value: float
    unit: str = Field(min_length=1, max_length=32)
    currency: str | None = Field(default=None, min_length=3, max_length=3)
    note: str = Field(min_length=3, max_length=2000)


# ---------- views ----------

def document_view(d: Document) -> dict:
    return {
        "id": d.id, "type": d.type, "fileName": d.file_name, "fileId": d.file_id, "sha256": d.sha256,
        "pageCount": d.page_count, "asOfDate": d.as_of_date.isoformat() if d.as_of_date else None,
        "status": d.status, "cpIds": d.active_cp_ids(), "latestJobId": d.latest_job_id,
        "uploadedBy": d.uploaded_by, "uploadedAt": d.uploaded_at.isoformat(),
    }


def term_view(t: ExtractedTerm) -> dict:
    return {
        "id": t.id, "documentId": t.document_id, "metricRaw": t.metric_raw,
        "covenantMasterId": t.covenant_master_id, "expression": t.expression, "operator": t.operator,
        "threshold": t.threshold, "unit": t.unit, "frequency": t.frequency, "category": t.category,
        "confidence": t.confidence, "method": t.method, "status": t.status,
        "issues": json.loads(t.issues_json or "[]"),
        "source": {"documentId": t.document_id, "page": t.page, "quote": t.quote},
        "reviewedBy": t.reviewed_by, "reviewNote": t.review_note,
    }


def job_view(j: Job) -> dict:
    return {
        "id": j.id, "documentId": j.document_id, "flow": j.flow, "status": j.status, "pool": j.pool,
        "attempts": j.attempts, "currentStep": j.current_step, "error": j.error,
        "steps": json.loads(j.steps_json or "[]"), "createdAt": j.created_at.isoformat(),
        "updatedAt": j.updated_at.isoformat() if j.updated_at else None,
    }


def value_view(v: StatementValue) -> dict:
    source = ({"documentId": v.document_id, "page": v.page, "cell": v.cell}
              if v.document_id else None)
    return {
        "id": v.id, "cpId": v.cp_id, "key": v.key, "value": v.value, "unit": v.unit, "currency": v.currency,
        "asOfDate": v.as_of_date.isoformat(), "derived": v.derived, "sourceType": v.source_type,
        "source": source, "note": v.note, "enteredBy": v.entered_by,
    }


# ---------- helpers ----------

def load_document(db: Session, document_id: str) -> Document:
    doc = db.get(Document, document_id)
    if doc is None:
        raise not_found("Document")
    return doc


def require_document_access(checker: CpAccessChecker, principal: Principal, doc: Document,
                            read_only: bool) -> None:
    """Allowed when the caller may access at least one CP the document is linked to."""
    for cp in doc.active_cp_ids():
        if checker.allowed(principal, cp, read_only):
            return
    raise not_found("Document")


def page_count(path: Path) -> int | None:
    if path.suffix.lower() != ".pdf":
        return None
    try:
        from pypdf import PdfReader
        return len(PdfReader(str(path)).pages)
    except Exception:  # noqa: BLE001 - unreadable PDFs are reported by the flow, not at upload
        return None


# ---------- routes ----------

@router.get("/health")
def health(request: Request) -> dict:
    db_status = "UP"
    try:
        with request.app.state.db.engine.connect() as conn:
            conn.execute(text("SELECT 1"))
    except Exception:  # noqa: BLE001
        db_status = "DOWN"
    return {"status": "UP" if db_status == "UP" else "DEGRADED", "database": db_status, "version": __version__}


@router.post("/dps/v1/recommendations")
def recommendations(body: RecommendationRequest, db: DB, user: User, checker: Checker) -> list[dict]:
    require_cp(checker, user, body.cpId, read_only=True)
    return services.recommendations(db, body.cpId)


@router.post("/dps/v1/values")
def values(body: ValuesRequest, db: DB, user: User, checker: Checker) -> dict:
    require_cp(checker, user, body.cpId, read_only=True)
    return services.values_series(db, body.cpId, body.keys, body.to, body.from_)


@router.get("/dps/v1/catalog")
def catalog(user: User) -> dict:  # noqa: ARG001 - authentication only
    return line_item_catalog()


class AiStatusRequest(BaseModel):
    cpId: str = Field(min_length=1)
    covenants: list[dict] = Field(default_factory=list)
    certificateFileIds: list[str] = Field(default_factory=list)


@router.post("/dps/v1/ai-suggestions")
def ai_suggestions(body: AiStatusRequest, request: Request, user: User) -> list[dict]:
    """C3: advisory status suggestions for Core's AiStatusRecommender (empty when nothing to say)."""
    require_roles(user, SERVICE, ADMIN)
    return request.app.state.advisor.suggest(body.cpId, body.covenants)


@router.post("/dps/v1/documents", status_code=202)
def upload_document(
    request: Request, db: DB, user: User, checker: Checker,
    file: Annotated[UploadFile, File()],
    cpIds: Annotated[list[str], Form()],  # noqa: N803 - contract field name
    type: Annotated[Literal["LOAN_AGREEMENT", "FINANCIAL_STATEMENT", "EVIDENCE"], Form()],  # noqa: A002
    asOfDate: Annotated[date | None, Form()] = None,  # noqa: N803
) -> JSONResponse:
    settings: Settings = request.app.state.settings
    store: FileStore = request.app.state.store
    cp_ids = sorted({c.strip() for c in cpIds if c and c.strip()})
    if not cp_ids:
        raise ApiError(400, "CP_REQUIRED", "At least one cpId is required")
    if type == "FINANCIAL_STATEMENT" and asOfDate is None:
        raise ApiError(400, "AS_OF_DATE_REQUIRED", "asOfDate (period end) is required for statements")
    name = Path(file.filename or "").name
    ext = Path(name).suffix.lower()
    if ext not in ALLOWED_EXTENSIONS:
        raise ApiError(400, "UNSUPPORTED_FILE_TYPE", f"Allowed types: {sorted(ALLOWED_EXTENSIONS)}")
    limit = settings.max_upload_mb * 1024 * 1024
    data = file.file.read(limit + 1)
    if len(data) > limit:
        raise ApiError(413, "FILE_TOO_LARGE", f"Maximum size is {settings.max_upload_mb} MB")
    if not data:
        raise ApiError(400, "EMPTY_FILE", "File is empty")
    for cp in cp_ids:
        require_cp(checker, user, cp, read_only=False)

    digest, rel = store.put(data, ext)
    existing = db.scalar(select(Document).where(Document.sha256 == digest, Document.type == type))
    if existing is not None:  # idempotent re-upload: just add any new links
        doc = existing
        _add_links(db, doc, cp_ids, user.user)
        db.commit()
        return JSONResponse(status_code=202, content={"documentId": doc.id, "jobId": doc.latest_job_id})

    doc = Document(type=type, file_name=name, storage_path=rel, sha256=digest, size_bytes=len(data),
                   page_count=page_count(store.path(rel)), as_of_date=asOfDate, uploaded_by=user.user)
    db.add(doc)
    db.flush()
    _add_links(db, doc, cp_ids, user.user)
    flow = FLOW_BY_TYPE.get(type)
    if flow:
        job = Job(document_id=doc.id, flow=flow)
        db.add(job)
        db.flush()
        doc.latest_job_id = job.id
    else:
        doc.status = "PUBLISHED"  # evidence is stored for reviewers; no processing flow
    services.audit(db, user.user, "UPLOAD", "document", doc.id, type=type, cpIds=cp_ids, sha256=digest)
    db.commit()
    return JSONResponse(status_code=202, content={"documentId": doc.id, "jobId": doc.latest_job_id})


def _add_links(db: Session, doc: Document, cp_ids: list[str], user: str) -> None:
    active = set(doc.active_cp_ids())
    for cp in cp_ids:
        if cp not in active:
            doc.links.append(DocumentCpLink(cp_id=cp, linked_by=user))


@router.get("/dps/v1/documents")
def list_documents(cpId: str, db: DB, user: User, checker: Checker) -> list[dict]:  # noqa: N803
    require_cp(checker, user, cpId, read_only=True)
    docs = db.scalars(select(Document).join(DocumentCpLink)
                      .where(DocumentCpLink.cp_id == cpId, DocumentCpLink.unlinked_at.is_(None))
                      .order_by(Document.uploaded_at.desc())).unique()
    return [document_view(d) for d in docs]


@router.get("/dps/v1/documents/{document_id}")
def get_document(document_id: str, db: DB, user: User, checker: Checker) -> dict:
    doc = load_document(db, document_id)
    require_document_access(checker, user, doc, read_only=True)
    return document_view(doc)


@router.post("/dps/v1/documents/{document_id}/links")
def link_document(document_id: str, body: LinkRequest, db: DB, user: User, checker: Checker) -> dict:
    doc = load_document(db, document_id)
    require_document_access(checker, user, doc, read_only=False)
    for cp in body.cpIds:
        require_cp(checker, user, cp, read_only=False)
    _add_links(db, doc, sorted(set(body.cpIds)), user.user)
    services.audit(db, user.user, "LINK", "document", doc.id, cpIds=body.cpIds)
    db.commit()
    return document_view(doc)


@router.delete("/dps/v1/documents/{document_id}/links")
def unlink_document(document_id: str, body: LinkRequest, db: DB, user: User, checker: Checker) -> dict:
    doc = load_document(db, document_id)
    for cp in body.cpIds:
        require_cp(checker, user, cp, read_only=False)
    now = utcnow()
    for link in doc.links:
        if link.cp_id in body.cpIds and link.unlinked_at is None:
            link.unlinked_at, link.unlinked_by = now, user.user
    services.audit(db, user.user, "UNLINK", "document", doc.id, cpIds=body.cpIds)
    db.commit()
    return document_view(doc)


@router.get("/dps/v1/documents/{document_id}/terms")
def list_terms(document_id: str, db: DB, user: User, checker: Checker) -> list[dict]:
    doc = load_document(db, document_id)
    require_document_access(checker, user, doc, read_only=True)
    terms = db.scalars(select(ExtractedTerm).where(ExtractedTerm.document_id == doc.id)
                       .order_by(ExtractedTerm.created_at))
    return [term_view(t) for t in terms]


@router.get("/dps/v1/documents/{document_id}/values")
def list_document_values(document_id: str, db: DB, user: User, checker: Checker) -> list[dict]:
    doc = load_document(db, document_id)
    require_document_access(checker, user, doc, read_only=True)
    rows = db.scalars(select(StatementValue).where(StatementValue.document_id == doc.id)
                      .order_by(StatementValue.key))
    return [value_view(v) for v in rows]


class AskRequest(BaseModel):
    question: str = Field(min_length=1, max_length=500)


@router.post("/dps/v1/ask")
def ask_ai(body: AskRequest, request: Request, user: User) -> dict:
    """FR-27: answers about the caller's own portfolio only (Core data fetched with the caller's identity)."""
    from dps.ask import answer

    advisor = request.app.state.advisor
    return answer(body.question, user, request.app.state.core_fetch, advisor.completion)


@router.get("/dps/v1/documents/{document_id}/narrative")
def document_narrative(document_id: str, request: Request, db: DB, user: User, checker: Checker) -> dict:
    """O8: an advisory reading of a statement's line items (never a compliance decision)."""
    doc = load_document(db, document_id)
    require_document_access(checker, user, doc, read_only=True)
    rows = db.scalars(select(StatementValue).where(StatementValue.document_id == doc.id,
                                                   StatementValue.superseded == false()))
    seen: dict[str, dict] = {}
    for v in rows:
        seen.setdefault(v.key, {"key": v.key, "value": v.value})
    return request.app.state.advisor.narrative(list(seen.values()))


@router.patch("/dps/v1/terms/{term_id}")
def review_term(term_id: str, body: TermReview, db: DB, user: User, checker: Checker) -> dict:
    term = db.get(ExtractedTerm, term_id)
    if term is None:
        raise not_found("Term")
    require_document_access(checker, user, load_document(db, term.document_id), read_only=False)
    if body.action == "EDIT":
        if body.expression is not None:
            term.expression = body.expression
        if body.covenantMasterId is not None:
            term.covenant_master_id = body.covenantMasterId
        term.method = "MANUAL"
        term.status = "NEEDS_REVIEW"
    else:
        term.status = "PUBLISHED" if body.action == "APPROVE" else "REJECTED"
    term.reviewed_by, term.review_note = user.user, body.note
    services.audit(db, user.user, f"TERM_{body.action}", "extracted_term", term.id, note=body.note)
    db.commit()
    return term_view(term)


MEDIA_TYPES = {".pdf": "application/pdf", ".csv": "text/csv",
               ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
               ".xls": "application/vnd.ms-excel"}


@router.get("/dps/v1/documents/{document_id}/file")
def document_file(document_id: str, request: Request, db: DB, user: User, checker: Checker) -> FileResponse:
    """FR-05: the original file, for the reviewer's page view (inline, never executed by the browser)."""
    doc = load_document(db, document_id)
    require_document_access(checker, user, doc, read_only=True)
    store: FileStore = request.app.state.store
    path = store.path(doc.storage_path)
    if not path.exists():
        raise not_found("File")
    media = MEDIA_TYPES.get(Path(doc.file_name).suffix.lower(), "application/octet-stream")
    return FileResponse(path, media_type=media, filename=doc.file_name, content_disposition_type="inline",
                        headers={"X-Content-Type-Options": "nosniff"})


@router.get("/dps/v1/jobs")
def list_jobs(db: DB, user: User, status: str | None = None, limit: int = 100) -> list[dict]:
    """FR-17: job operations for support: newest first, optionally one status (e.g. FAILED)."""
    require_roles(user, SUPPORT, ADMIN)
    q = select(Job).order_by(Job.created_at.desc()).limit(max(1, min(limit, 500)))
    if status:
        q = q.where(Job.status == status.upper())
    return [job_view(j) for j in db.scalars(q)]


@router.post("/dps/v1/jobs/{job_id}/retry", status_code=202)
def retry_job(job_id: str, db: DB, user: User) -> dict:
    """FR-17: put a FAILED job back in the queue with fresh attempts; audited."""
    require_roles(user, SUPPORT, ADMIN)
    job = db.get(Job, job_id)
    if job is None:
        raise not_found("Job")
    if job.status != "FAILED":
        raise ApiError(409, "JOB_NOT_FAILED", f"Job is {job.status}, not FAILED")
    job.status, job.attempts, job.error, job.updated_at = "QUEUED", 0, None, utcnow()
    doc = db.get(Document, job.document_id)
    if doc is not None:
        doc.status = "QUEUED"
    services.audit(db, user.user, "JOB_RETRY", "job", job.id)
    db.commit()
    return job_view(job)


@router.get("/dps/v1/jobs/{job_id}")
def get_job(job_id: str, db: DB, user: User, checker: Checker) -> dict:
    job = db.get(Job, job_id)
    if job is None:
        raise not_found("Job")
    require_document_access(checker, user, load_document(db, job.document_id), read_only=True)
    return job_view(job)


@router.post("/dps/v1/jobs/{job_id}/resume", status_code=202)
def resume_job(job_id: str, db: DB, user: User, checker: Checker) -> dict:
    job = db.get(Job, job_id)
    if job is None:
        raise not_found("Job")
    require_document_access(checker, user, load_document(db, job.document_id), read_only=False)
    if job.status != "NEEDS_REVIEW":
        raise ApiError(409, "JOB_NOT_WAITING", f"Job is {job.status}, not NEEDS_REVIEW")
    job.status, job.updated_at = "QUEUED", utcnow()
    services.audit(db, user.user, "JOB_RESUME", "job", job.id)
    db.commit()
    return job_view(job)


@router.post("/dps/v1/cps/{cp_id}/manual-values", status_code=201)
def manual_value(cp_id: str, body: ManualValue, request: Request, db: DB, user: User,
                 checker: Checker) -> dict:
    require_roles(user, APPROVER, ADMIN)
    require_cp(checker, user, cp_id, read_only=False)
    row = services.store_manual_value(
        db, cp_id=cp_id, key=body.key, value=body.value, unit=body.unit, currency=body.currency,
        as_of=body.asOfDate, note=body.note, user=user.user,
        data_source_id=request.app.state.settings.core_data_source_id)
    db.commit()
    return value_view(row)
