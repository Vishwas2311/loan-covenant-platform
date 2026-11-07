"""Business logic behind the API (no FastAPI types here)."""

from __future__ import annotations

import json
from collections import defaultdict
from datetime import date

from sqlalchemy import false, func, select
from sqlalchemy.orm import Session

from dps.models import AuditEvent, Document, DocumentCpLink, EventOutbox, ExtractedTerm, StatementValue, utcnow


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


def _covenant_key(term: ExtractedTerm) -> str:
    """Which covenant a term is about: its library covenant, or its own name when it is a custom one."""
    return term.covenant_master_id or term.metric_raw.lower()


_UNIT_TEXT = {"PERCENT": "%", "%": "%", "INR_CR": " INR crore", "USD": " USD"}
_OPERATOR_TEXT = {">=": "at least", ">": "more than", "<=": "at most", "<": "less than"}


def _manual_rule(term: ExtractedTerm) -> str | None:
    """A covenant a person assesses (Core MANUAL: free text) still carries its limit, in words."""
    if term.operator is None or term.threshold is None:
        return None
    unit = _UNIT_TEXT.get(term.unit or "", "")
    limit = f"{_OPERATOR_TEXT.get(term.operator, term.operator)} {term.threshold:g}{unit}"
    return (f"{term.name or term.metric_raw} must be {limit}. No statement line gives this figure: a person enters it "
            f"each period (agreement page {term.page}).")


def recommendations(db: Session, cp_id: str) -> list[dict]:
    """C1: PUBLISHED proposals from loan agreements actively linked to the CP.

    C7: where a linked agreement amends another, the amendment's term replaces the earlier agreement's
    term for the same covenant (through any chain of amendments); covenants the amendment does not
    mention keep their earlier terms.
    """
    stmt = (select(ExtractedTerm, Document)
            .join(Document, Document.id == ExtractedTerm.document_id)
            .join(DocumentCpLink, DocumentCpLink.document_id == Document.id)
            .where(DocumentCpLink.cp_id == cp_id, DocumentCpLink.unlinked_at.is_(None),
                   Document.type == "LOAN_AGREEMENT", ExtractedTerm.status == "PUBLISHED",
                   ExtractedTerm.category != "INFO")  # FR-29/30 facts are review information, not covenants
            .order_by(ExtractedTerm.created_at))
    rows = db.execute(stmt).all()
    amended_by: dict[str, list[str]] = defaultdict(list)
    for doc in {d.id: d for _, d in rows}.values():
        if doc.amends_document_id:
            amended_by[doc.amends_document_id].append(doc.id)
    keys_by_doc: dict[str, set[str]] = defaultdict(set)
    for term, doc in rows:
        keys_by_doc[doc.id].add(_covenant_key(term))

    def replaced(doc_id: str, key: str, seen: frozenset[str] = frozenset()) -> bool:
        return any(key in keys_by_doc[later] or replaced(later, key, seen | {doc_id})
                   for later in amended_by.get(doc_id, ()) if later not in seen)

    out = []
    for term, doc in rows:
        if replaced(doc.id, _covenant_key(term)):
            continue
        custom = None
        if term.covenant_master_id is None:
            custom = {"name": term.name or term.metric_raw, "definition": term.expression or _manual_rule(term),
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


def remove_document(db: Session, doc: Document, *, user: str, reason: str, data_source_id: str) -> None:
    """B3: take a wrongly uploaded document out of use without deleting it.

    The document is marked REMOVED and kept for the audit trail. Its statement values stop being served;
    where it had replaced an earlier value for the same CP, line and period, that earlier value is served
    again. Its proposed covenants are withdrawn. Core is told about each changed CP and period, so the
    covenants that used these figures are checked again.
    """
    doc.status = "REMOVED"
    touched: set[tuple[str, date]] = set()
    for row in db.scalars(select(StatementValue).where(StatementValue.document_id == doc.id,
                                                       StatementValue.superseded == false())):
        row.superseded = True
        touched.add((row.cp_id, row.as_of_date))
        db.flush()
        still_active = db.scalar(select(func.count()).select_from(StatementValue).where(
            StatementValue.cp_id == row.cp_id, StatementValue.key == row.key,
            StatementValue.as_of_date == row.as_of_date, StatementValue.superseded == false()))
        if not still_active:
            earlier = db.scalars(
                select(StatementValue)
                .outerjoin(Document, Document.id == StatementValue.document_id)
                .where(StatementValue.cp_id == row.cp_id, StatementValue.key == row.key,
                       StatementValue.as_of_date == row.as_of_date, StatementValue.id != row.id,
                       (StatementValue.document_id.is_(None)) | (Document.status != "REMOVED"))
                .order_by(StatementValue.created_at.desc())).first()
            if earlier is not None:
                earlier.superseded = False
    for term in db.scalars(select(ExtractedTerm).where(ExtractedTerm.document_id == doc.id,
                                                       ExtractedTerm.status != "REJECTED")):
        term.status = "WITHDRAWN"
    for cp_id, as_of in sorted(touched):
        db.add(EventOutbox(type="DATA_EVENT", payload_json=json.dumps({
            "dataSourceId": data_source_id, "entityKey": cp_id, "entityKeyLevel": "CP_ID",
            "asOfDate": as_of.isoformat(), "changedAt": utcnow().isoformat(), "documentId": doc.id})))
    audit(db, user, "REMOVE", "document", doc.id, reason=reason, values=len(touched))


def spread(db: Session, doc: Document) -> list[dict]:
    """C6: the full spread for a statement, section by section, from the values it currently serves.

    A line the reader found has status MAPPED (confidence 1.0, or 0.8 when it was worked out from other
    lines); one a reviewer typed in is CONFIRMED; one with no figure is UNMAPPED.
    """
    from dps.catalog import LINE_ITEMS
    from dps.extraction.statement import SECTIONS

    cps = doc.active_cp_ids()
    rows = list(db.scalars(select(StatementValue).where(
        StatementValue.document_id == doc.id, StatementValue.superseded == false(),
        StatementValue.cp_id == (cps[0] if cps else "")).order_by(StatementValue.created_at)))
    by_key = {r.key: r for r in rows}
    out = []
    for section, keys in SECTIONS.items():
        for key in keys:
            r = by_key.get(key)
            if r is None:
                status, confidence = "UNMAPPED", 0.0
            elif r.source_type == "USER":
                status, confidence = "CONFIRMED", 1.0
            else:
                status, confidence = "MAPPED", 0.8 if r.derived else 1.0
            out.append({"section": section, "standardKey": key, "standardLabel": LINE_ITEMS[key],
                        "sourceLabel": "worked out from other lines" if r is not None and r.derived else None,
                        "value": r.value if r else None, "unit": r.unit if r else None,
                        "cell": r.cell if r else None, "page": r.page if r else None,
                        "confidence": confidence, "status": status})
    return out


def correct_spread(db: Session, doc: Document, values: dict[str, float], *, user: str, note: str,
                   data_source_id: str) -> None:
    """C6: store reviewer values for a statement, for every CP it applies to, and tell Core about them."""
    for cp in doc.active_cp_ids():
        for key, value in values.items():
            for old in db.scalars(select(StatementValue).where(
                    StatementValue.cp_id == cp, StatementValue.key == key, StatementValue.as_of_date == doc.as_of_date,
                    StatementValue.superseded == false())):
                old.superseded = True
            db.add(StatementValue(cp_id=cp, key=key, value=value, unit="INR_CR", currency="INR",
                                  as_of_date=doc.as_of_date, source_type="USER", document_id=doc.id, note=note,
                                  entered_by=user))
        db.add(EventOutbox(type="DATA_EVENT", payload_json=json.dumps({
            "dataSourceId": data_source_id, "entityKey": cp, "entityKeyLevel": "CP_ID",
            "asOfDate": doc.as_of_date.isoformat(), "changedAt": utcnow().isoformat(), "documentId": doc.id})))
    audit(db, user, "SPREAD_CORRECTED", "document", doc.id, keys=sorted(values), note=note)


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
