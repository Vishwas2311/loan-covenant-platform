"""Flow B: Periodic Assessment (data side) — Parse → Extract → Calculate → Validate → Report (03 §3).

Report stores versioned values (a new upload for the same CP, key and period supersedes the old one) and
queues a C5 data-event per linked CP so Core re-assesses at once. Compliance is still decided by Core.
"""

from __future__ import annotations

import json
import logging
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from crewai.flow.flow import Flow, listen, router, start
from pydantic import BaseModel, Field, PrivateAttr
from sqlalchemy import false, select
from sqlalchemy.orm import Session, sessionmaker

from dps import services
from dps.extraction.statement import (
    TO_CRORE,
    LineValue,
    StatementResult,
    extract_values,
    header_from_csv,
    header_from_xls,
    header_from_xlsx,
    likely_lines,
    rows_from_csv,
    rows_from_text,
    rows_from_xls,
    rows_from_xlsx,
)
from dps.extraction.statement_pdf import PdfStatement
from dps.flows.persistence import SqlFlowPersistence
from dps.models import Document, EventOutbox, StatementValue, utcnow
from dps.parsing import ParseError, needs_heavy

ACTOR = "dps-flow-b"
# Lines compared with the CP's previous period, and the change that means a unit misread (lakhs read as crore is
# 100 times, rupees read as thousands 1,000 times; a real business rarely moves 20 times in a year).
SCALE_KEYS = ("REVENUE", "TOTAL_ASSETS", "NET_WORTH", "PAT", "CURRENT_ASSETS")
SCALE_JUMP = 20.0
# A scan read below this mean OCR confidence is held for a person to confirm the figures. Benchmark: clean
# scans read at 0.986-0.993 with every value right; a blurred 100 dpi scan read at 0.952 and lost 3 of 12 lines.
OCR_REVIEW_QUALITY = 0.97
log = logging.getLogger(__name__)


@dataclass
class StatementDeps:
    session_factory: sessionmaker[Session]
    read_file: Callable[[Document], bytes]
    parser: object
    data_source_id: str
    heavy_pool: bool
    # Table-aware PDF reader (extraction.statement_pdf.read_pdf_statement); None: plain text lines only.
    pdf_reader: Callable[[bytes], PdfStatement] | None = None
    # Optional second reader (extraction.statement_ai.check): (page text, values) -> disagreements to review.
    second_reader: Callable[[str, list[LineValue]], list[str]] | None = None


class StatementState(BaseModel):
    id: str = ""
    job_id: str = ""
    document_id: str = ""
    file_kind: str = ""
    header_text: str = ""
    page_text: str = ""
    rows: list[list] = Field(default_factory=list)
    values: list[dict] = Field(default_factory=list)
    unit_scale: str = ""
    likely: list[dict] = Field(default_factory=list)  # possible lines for covenant inputs not found
    issues: list[str] = Field(default_factory=list)
    route: str = ""
    parse_issues: list[str] = Field(default_factory=list)
    error: str = ""
    outcome: Literal["", "REPORTED", "NEEDS_REVIEW", "FAILED", "HEAVY"] = ""


class StatementProcessingFlow(Flow[StatementState]):
    _deps: StatementDeps = PrivateAttr()

    def __init__(self, deps: StatementDeps, **kwargs) -> None:
        kwargs.setdefault("suppress_flow_events", True)  # workers log job steps; no console panels
        super().__init__(persistence=SqlFlowPersistence(deps.session_factory), **kwargs)
        self._deps = deps

    def _checkpoint(self, step: str) -> None:
        self.persistence.save_state(self.state.id, step, self.state)

    @start()
    def begin(self) -> str:
        return self.state.outcome

    @router(begin)
    def entry(self) -> Literal["resume", "parse"]:
        return "resume" if self.state.outcome == "NEEDS_REVIEW" else "parse"

    @listen("parse")
    def parse_document(self) -> None:
        with self._deps.session_factory() as db:
            doc = db.get(Document, self.state.document_id)
            data, ext = self._deps.read_file(doc), Path(doc.file_name).suffix.lower()
        self.state.file_kind = ext.lstrip(".")
        try:
            if ext == ".pdf":
                table = self._read_table(data)
                if table is not None and table.rows:
                    rows, self.state.header_text = table.rows, table.header_text
                    self.state.page_text = "\n\n".join(table.texts)[:60000]
                    if table.ocr_quality is not None and table.ocr_quality < OCR_REVIEW_QUALITY:
                        self.state.parse_issues = [
                            f"LOW_OCR_QUALITY: scanned pages read at {table.ocr_quality:.0%} confidence"]
                else:
                    pages = self._deps.parser.parse(data)
                    if not self._deps.heavy_pool and needs_heavy(pages):
                        self.state.route = "heavy"
                    rows = rows_from_text([(p.no, p.text) for p in pages])
                    self.state.header_text = " ".join(p.text[:600] for p in pages[:2])
            elif ext == ".xlsx":
                rows = rows_from_xlsx(data)
                self.state.header_text = header_from_xlsx(data)
            elif ext == ".xls":
                rows = rows_from_xls(data)
                self.state.header_text = header_from_xls(data)
            elif ext == ".csv":
                rows = rows_from_csv(data)
                self.state.header_text = header_from_csv(data)
            else:
                raise ParseError(f"{ext} statements are not supported; upload PDF, XLSX, XLS or CSV")
        except ParseError as ex:
            self.state.error, rows = str(ex), []
        except Exception as ex:  # corrupt workbook / CSV
            self.state.error, rows = f"Unreadable statement: {type(ex).__name__}", []
        self.state.rows = [list(r) for r in rows]
        self._checkpoint("parse")

    def _read_table(self, data: bytes) -> PdfStatement | None:
        if self._deps.pdf_reader is None:
            return None
        try:
            return self._deps.pdf_reader(data)
        except Exception as ex:  # an odd PDF: the plain-text reader below still gets a go
            log.warning("Table reader failed, using text lines: %s", type(ex).__name__)
            return None

    @listen(parse_document)
    def extract_and_calculate(self) -> None:
        result: StatementResult = extract_values([tuple(r) for r in self.state.rows], self.state.header_text)
        self.state.values = [v.as_dict() for v in result.values]
        self.state.unit_scale = result.unit_scale
        self.state.issues = result.issues + self.state.parse_issues + self._scale_jumps(result.values)
        found = {v.key for v in result.values if not v.derived}
        factor = TO_CRORE.get(result.unit_scale, 1.0)
        self.state.likely = likely_lines([tuple(r) for r in self.state.rows], found, factor) if result.values else []
        if self._deps.second_reader is not None and self.state.page_text and result.values:
            self.state.issues += self._deps.second_reader(self.state.page_text, result.values)
        self._checkpoint("calculate")

    def _scale_jumps(self, values: list[LineValue]) -> list[str]:
        """A figure 20 times larger or smaller than the same CP's previous period: the unit was probably misread."""
        with self._deps.session_factory() as db:
            doc = db.get(Document, self.state.document_id)
            previous = services.previous_values(db, doc.active_cp_ids(), doc.as_of_date, SCALE_KEYS)
        out = []
        for v in values:
            before = previous.get(v.key)
            if v.derived or before is None or not before[0] or not v.value:
                continue
            ratio = abs(v.value / before[0])
            if ratio > SCALE_JUMP or ratio < 1 / SCALE_JUMP:
                out.append(f"SCALE_JUMP: {v.key} {v.value} against {before[0]} for {before[1].isoformat()}")
        return out

    @router(extract_and_calculate)
    def validate(self) -> Literal["report", "needs_review", "failed", "heavy"]:
        if self.state.route == "heavy":
            return "heavy"
        if self.state.error or not self.state.rows:
            route = "failed"
        elif self.state.issues:
            route = "needs_review"
        else:
            route = "report"
        self.state.route = route
        self._checkpoint("validate")
        return route

    @listen("report")
    def report_values(self) -> None:
        self._report()
        self.state.outcome = "REPORTED"
        self._checkpoint("report")

    @listen("needs_review")
    def hold(self) -> None:
        with self._deps.session_factory() as db:
            doc = db.get(Document, self.state.document_id)
            doc.status = "NEEDS_REVIEW"
            doc.review_json = self._review_json()
            services.audit(db, ACTOR, "STATEMENT_NEEDS_REVIEW", "document", doc.id, issues=self.state.issues)
            db.commit()
        self.state.outcome = "NEEDS_REVIEW"
        self._checkpoint("hold")

    @listen("resume")
    def finish_review(self) -> None:
        """A reviewer looked at the issues (and may have entered manual values): report what was read."""
        self._report()
        self.state.outcome = "REPORTED"
        self._checkpoint("resume")

    @listen("failed")
    def fail(self) -> None:
        self.state.error = self.state.error or "No line items found in the statement"
        with self._deps.session_factory() as db:
            db.get(Document, self.state.document_id).status = "FAILED"
            db.commit()
        self.state.outcome = "FAILED"
        self._checkpoint("fail")

    @listen("heavy")
    def hand_to_heavy_pool(self) -> None:
        self.state.route = ""
        self.state.outcome = "HEAVY"
        self._checkpoint("heavy")

    def _review_json(self) -> str:
        return json.dumps({"issues": self.state.issues, "unitScale": self.state.unit_scale or None,
                           "likely": self.state.likely})

    def _report(self) -> None:
        """Idempotent: re-running for the same document replaces its own values, never duplicates them."""
        values = [LineValue(**v) for v in self.state.values]
        with self._deps.session_factory() as db:
            doc = db.get(Document, self.state.document_id)
            cp_ids = doc.active_cp_ids()
            for cp in cp_ids:
                for old in db.scalars(select(StatementValue).where(
                        StatementValue.cp_id == cp, StatementValue.as_of_date == doc.as_of_date,
                        StatementValue.key.in_([v.key for v in values]), StatementValue.superseded == false())):
                    if old.document_id == doc.id:
                        db.delete(old)
                    else:
                        old.superseded = True
                db.flush()
                for v in values:
                    db.add(StatementValue(cp_id=cp, key=v.key, value=v.value, unit=v.unit, currency="INR",
                                          as_of_date=doc.as_of_date, derived=v.derived, source_type="DOCUMENT",
                                          document_id=doc.id, page=v.page, cell=v.cell))
                db.add(EventOutbox(type="DATA_EVENT", payload_json=json.dumps({
                    "dataSourceId": self._deps.data_source_id, "entityKey": cp, "entityKeyLevel": "CP_ID",
                    "asOfDate": doc.as_of_date.isoformat(), "changedAt": utcnow().isoformat(),
                    "documentId": doc.id})))
            doc.status = "PUBLISHED"
            doc.review_json = self._review_json()
            services.audit(db, ACTOR, "STATEMENT_REPORTED", "document", doc.id, values=len(values), cpIds=cp_ids,
                           issues=self.state.issues)
            db.commit()
