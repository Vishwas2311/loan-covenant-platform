"""Flow A: Agreement Intake — Parse → Extract → Normalize → Validate → Publish (03-flows-design.md §2).

Deterministic end to end (no LLM). The flow never applies a covenant: it publishes proposals that Core
pulls through C1, and the RM confirms them in the onboarding grid.
"""

from __future__ import annotations

import json
import logging
from collections.abc import Callable
from dataclasses import dataclass
from typing import Literal

from crewai.flow.flow import Flow, listen, router, start
from pydantic import BaseModel, Field, PrivateAttr
from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from dps import services
from dps.core_client import CoreClient
from dps.extraction.agreement import extract_terms
from dps.extraction.coverage import possible_covenants
from dps.extraction.mapping import Proposal, normalize
from dps.extraction.terms_info import extract_info
from dps.extraction.validation import FAILED, NEEDS_REVIEW, PUBLISH, validate
from dps.flows.persistence import SqlFlowPersistence
from dps.models import Document, ExtractedTerm
from dps.parsing import Page, ParseError, needs_heavy

log = logging.getLogger(__name__)

ACTOR = "dps-flow-a"


@dataclass
class IntakeDeps:
    session_factory: sessionmaker[Session]
    read_file: Callable[[Document], bytes]
    parser: object  # ParserEngine or DoclingEngine: .parse(bytes) -> list[Page]
    core: CoreClient
    min_confidence: float
    heavy_pool: bool  # True on dps-worker-heavy: never hand off again


class IntakeState(BaseModel):
    id: str = ""
    job_id: str = ""
    document_id: str = ""
    pages: list[dict] = Field(default_factory=list)
    terms: list[dict] = Field(default_factory=list)
    proposals: list[dict] = Field(default_factory=list)
    route: str = ""
    error: str = ""
    outcome: Literal["", "PUBLISHED", "NEEDS_REVIEW", "FAILED", "HEAVY"] = ""


class AgreementIntakeFlow(Flow[IntakeState]):
    _deps: IntakeDeps = PrivateAttr()

    def __init__(self, deps: IntakeDeps, **kwargs) -> None:
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
            data = self._deps.read_file(doc)
        try:
            pages = self._deps.parser.parse(data)
        except ParseError as ex:
            self.state.error = str(ex)
            pages = []
        self.state.pages = [p.as_dict() for p in pages]
        self._checkpoint("parse")

    @listen(parse_document)
    def extract(self) -> None:
        pages = self._pages()
        terms = extract_terms(pages)
        # Safety net: covenant-shaped sentences no term explains go to a person as possible covenants.
        candidates, _ = possible_covenants(pages, terms)
        self.state.terms = [t.as_dict() for t in terms + candidates + extract_info(pages)]
        self._checkpoint("extract")

    @listen(extract)
    def normalize_terms(self) -> None:
        from dps.extraction.agreement import Term

        terms = [Term(**t) for t in self.state.terms]
        masters = self._deps.core.active_masters() if terms else []
        self.state.proposals = [p.as_dict() for p in normalize(terms, masters)]
        self._checkpoint("normalize")

    @router(normalize_terms)
    def validate_proposals(self) -> Literal["publish", "needs_review", "failed", "heavy"]:
        pages = self._pages()
        if not self._deps.heavy_pool and pages and needs_heavy(pages) and not self.state.error:
            self.state.route = "heavy"
            return "heavy"
        proposals = [Proposal(**p) for p in self.state.proposals]
        route = validate(pages, proposals, self._deps.core.validate_formula, self._deps.min_confidence)
        self.state.proposals = [p.as_dict() for p in proposals]
        self.state.route = route
        self._checkpoint("validate")
        return route

    @listen(PUBLISH)
    def publish_proposals(self) -> None:
        self._store(document_status="PUBLISHED")
        self.state.outcome = "PUBLISHED"
        self._checkpoint("publish")

    @listen(NEEDS_REVIEW)
    def hold(self) -> None:
        self._store(document_status="NEEDS_REVIEW")
        self.state.outcome = "NEEDS_REVIEW"
        self._checkpoint("hold")

    @listen(FAILED)
    def fail(self) -> None:
        self.state.error = self.state.error or "No text found in the document, even after OCR"
        with self._deps.session_factory() as db:
            db.get(Document, self.state.document_id).status = "FAILED"
            db.commit()
        self.state.outcome = "FAILED"
        self._checkpoint("fail")

    @listen("heavy")
    def hand_to_heavy_pool(self) -> None:
        self.state.outcome = "HEAVY"
        self._checkpoint("heavy")

    @listen("resume")
    def finish_review(self) -> None:
        """After review: the run completes once no term is left waiting for a decision."""
        with self._deps.session_factory() as db:
            waiting = db.scalars(select(ExtractedTerm.id).where(
                ExtractedTerm.document_id == self.state.document_id,
                ExtractedTerm.status.in_(("NEEDS_REVIEW", "PROPOSED")))).first()
            doc = db.get(Document, self.state.document_id)
            if waiting is None:
                doc.status = "PUBLISHED"
                self.state.outcome = "PUBLISHED"
            db.commit()
        self._checkpoint("resume")

    def _pages(self) -> list[Page]:
        return [Page(**p) for p in self.state.pages]

    def _store(self, document_status: str) -> None:
        """Upserts this document's REGEX proposals; terms a person already edited or decided are kept."""
        with self._deps.session_factory() as db:
            existing = list(db.scalars(select(ExtractedTerm).where(
                ExtractedTerm.document_id == self.state.document_id)))
            decided = {t.metric_raw for t in existing if t.reviewed_by}
            for t in existing:
                if not t.reviewed_by:
                    db.delete(t)
            count = 0
            for p in self.state.proposals:
                if p["metric_raw"] in decided:
                    continue
                db.add(ExtractedTerm(
                    document_id=self.state.document_id, metric_raw=p["metric_raw"], name=(p.get("name") or "")[:200],
                    covenant_master_id=p["covenant_master_id"], expression=p["expression"],
                    operator=p["operator"], threshold=p["threshold"], unit=p["unit"], frequency=p["frequency"],
                    category=p["category"], confidence=p["confidence"], method=p["method"],
                    status="NEEDS_REVIEW" if p["issues"] else "PUBLISHED",
                    issues_json=json.dumps(p["issues"]), page=p["page"], quote=(p["quote"] or "")[:500]))
                count += 1
            doc = db.get(Document, self.state.document_id)
            doc.status = document_status
            doc.page_count = len(self.state.pages)
            services.audit(db, ACTOR, "INTAKE_" + document_status, "document", doc.id,
                           proposals=count, job=self.state.job_id)
            db.commit()
