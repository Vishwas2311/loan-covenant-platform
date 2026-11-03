"""Business logic behind the API (no FastAPI types here)."""

from __future__ import annotations

import json
from collections import defaultdict
from datetime import date

from sqlalchemy import false, func, select
from sqlalchemy.orm import Session

from dps.models import AuditEvent, Document, DocumentCpLink, EventOutbox, ExtractedTerm, StatementValue


def audit(db: Session, actor: str, action: str, target_type: str, target_id: str, **detail) -> None:
    db.add(AuditEvent(actor=actor, action=action, target_type=target_type, target_id=target_id,
                      detail_json=json.dumps(detail, default=str)))


def values_series(db: Session, entity: str, keys: list[str], to: date, frm: date | None) -> dict:
    """C2: Core's REST data-source contract.

    Point-in-time (``frm`` is None): latest value per key with as_of_date <= to.
    Windowed: every value with frm <= as_of_date <= to. Superseded values are never served.
    """
    base = select(StatementValue).where(
        StatementValue.cp_id == entity,
        StatementValue.key.in_(keys),
        StatementValue.as_of_date <= to,
        StatementValue.superseded == false(),
    )
    if frm is not None:
        base = base.where(StatementValue.as_of_date >= frm)
    rows = list(db.scalars(base.order_by(StatementValue.as_of_date, StatementValue.created_at)))
    if frm is None:
        latest: dict[str, StatementValue] = {}
        for r in rows:
            latest[r.key] = r  # ordered ascending, so the last one wins
        rows = list(latest.values())
    by_date: dict[date, dict] = defaultdict(dict)
    for r in rows:
        by_date[r.as_of_date][r.key] = r.value
    points = [{"asOfDate": d.isoformat(), "values": v} for d, v in sorted(by_date.items())]
    present = {r.key for r in rows}
    changed = db.scalar(select(func.max(StatementValue.created_at)).where(StatementValue.cp_id == entity))
    return {
        "cpId": entity,
        "points": points,
        "missingKeys": [k for k in keys if k not in present],
        "sourceChangedAt": changed.isoformat() if changed else None,
    }


def recommendations(db: Session, cp_id: str) -> list[dict]:
    """C1: PUBLISHED proposals from loan agreements actively linked to the CP."""
    stmt = (select(ExtractedTerm, Document)
            .join(Document, Document.id == ExtractedTerm.document_id)
            .join(DocumentCpLink, DocumentCpLink.document_id == Document.id)
            .where(DocumentCpLink.cp_id == cp_id, DocumentCpLink.unlinked_at.is_(None),
                   Document.type == "LOAN_AGREEMENT", ExtractedTerm.status == "PUBLISHED",
                   ExtractedTerm.category != "INFO")  # FR-29/30 facts are review information, not covenants
            .order_by(ExtractedTerm.created_at))
    out = []
    for term, doc in db.execute(stmt).all():
        custom = None
        if term.covenant_master_id is None:
            custom = {"name": term.metric_raw, "definition": term.expression,
                      "assessmentType": "AUTO" if term.category == "FINANCIAL" and term.expression else "MANUAL"}
        out.append({
            "covenantMasterId": term.covenant_master_id,
            "recommendedExpression": term.expression if term.covenant_master_id else None,
            "confidence": term.confidence,
            "rationale": term.quote,
            "frequency": term.frequency,
            "source": {"documentId": doc.id, "fileId": doc.file_id, "page": term.page, "quote": term.quote},
            "customCovenant": custom,
        })
    return out


def store_manual_value(db: Session, *, cp_id: str, key: str, value: float, unit: str, currency: str | None,
                       as_of: date, note: str, user: str, data_source_id: str) -> StatementValue:
    """FR-20: approver-entered value. Supersedes the current value for the same key/period and queues C5."""
    for old in db.scalars(select(StatementValue).where(
            StatementValue.cp_id == cp_id, StatementValue.key == key,
            StatementValue.as_of_date == as_of, StatementValue.superseded == false())):
        old.superseded = True
    row = StatementValue(cp_id=cp_id, key=key, value=value, unit=unit, currency=currency, as_of_date=as_of,
                         source_type="USER", note=note, entered_by=user)
    db.add(row)
    db.flush()
    db.add(EventOutbox(type="DATA_EVENT", payload_json=json.dumps({
        "dataSourceId": data_source_id, "entityKey": cp_id, "entityKeyLevel": "CP_ID",
        "asOfDate": as_of.isoformat(), "changedAt": row.created_at.isoformat(), "documentId": None})))
    audit(db, user, "MANUAL_VALUE", "statement_value", row.id, key=key, value=value, as_of=as_of, note=note)
    return row
