"""Database tables (schema ``dps_*``). Owned by DPS; Core never writes here."""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime

from sqlalchemy import Boolean, Date, DateTime, Float, ForeignKey, Index, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship
from sqlalchemy.types import TypeDecorator

from dps.db import Base


def new_id() -> str:
    return str(uuid.uuid4())


def utcnow() -> datetime:
    return datetime.now(UTC)


class UtcDateTime(TypeDecorator):
    """Timestamp stored in UTC and always read back timezone-aware.

    SQLite drops the offset even for ``DateTime(timezone=True)``; a naive value serialised for Core
    (java.time.Instant) or the UI would then be unparseable or read as local time.
    """

    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect) -> datetime | None:
        return value.astimezone(UTC) if value is not None and value.tzinfo else value

    def process_result_value(self, value: datetime | None, dialect) -> datetime | None:
        if value is None or value.tzinfo:
            return value
        return value.replace(tzinfo=UTC)


class Document(Base):
    __tablename__ = "dps_document"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    type: Mapped[str] = mapped_column(String(32))  # LOAN_AGREEMENT | FINANCIAL_STATEMENT | EVIDENCE
    file_name: Mapped[str] = mapped_column(String(255))
    file_id: Mapped[str | None] = mapped_column(String(255))  # external file service id (later)
    storage_path: Mapped[str] = mapped_column(String(500))
    sha256: Mapped[str] = mapped_column(String(64), index=True)
    size_bytes: Mapped[int] = mapped_column(Integer)
    page_count: Mapped[int | None] = mapped_column(Integer)
    as_of_date: Mapped[date | None] = mapped_column(Date)
    status: Mapped[str] = mapped_column(String(24), default="QUEUED")
    uploaded_by: Mapped[str] = mapped_column(String(255))
    uploaded_at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)
    latest_job_id: Mapped[str | None] = mapped_column(String(36))

    links: Mapped[list[DocumentCpLink]] = relationship(back_populates="document", cascade="all, delete-orphan")

    def active_cp_ids(self) -> list[str]:
        return sorted({lk.cp_id for lk in self.links if lk.unlinked_at is None})


class DocumentCpLink(Base):
    """One document can serve several CPs (decision D7)."""

    __tablename__ = "dps_document_cp_link"
    __table_args__ = (Index("ix_dps_link_cp", "cp_id"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    document_id: Mapped[str] = mapped_column(ForeignKey("dps_document.id"))
    cp_id: Mapped[str] = mapped_column(String(64))
    linked_by: Mapped[str] = mapped_column(String(255))
    linked_at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)
    unlinked_by: Mapped[str | None] = mapped_column(String(255))
    unlinked_at: Mapped[datetime | None] = mapped_column(UtcDateTime())

    document: Mapped[Document] = relationship(back_populates="links")


class Job(Base):
    __tablename__ = "dps_job"
    __table_args__ = (Index("ix_dps_job_claim", "status", "pool"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    document_id: Mapped[str] = mapped_column(ForeignKey("dps_document.id"))
    flow: Mapped[str] = mapped_column(String(32))  # AGREEMENT_INTAKE | STATEMENT_PROCESSING
    status: Mapped[str] = mapped_column(String(16), default="QUEUED")
    pool: Mapped[str] = mapped_column(String(16), default="STANDARD")  # STANDARD | HEAVY
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    current_step: Mapped[str | None] = mapped_column(String(32))
    error: Mapped[str | None] = mapped_column(Text)
    steps_json: Mapped[str] = mapped_column(Text, default="[]")
    flow_state_id: Mapped[str | None] = mapped_column(String(64))
    locked_by: Mapped[str | None] = mapped_column(String(64))
    locked_until: Mapped[datetime | None] = mapped_column(UtcDateTime())
    created_at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)
    updated_at: Mapped[datetime | None] = mapped_column(UtcDateTime())


class ExtractedTerm(Base):
    __tablename__ = "dps_extracted_term"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    document_id: Mapped[str] = mapped_column(ForeignKey("dps_document.id"), index=True)
    metric_raw: Mapped[str] = mapped_column(String(128))
    covenant_master_id: Mapped[str | None] = mapped_column(String(64))
    expression: Mapped[str | None] = mapped_column(String(2000))
    operator: Mapped[str | None] = mapped_column(String(4))
    threshold: Mapped[float | None] = mapped_column(Float)
    unit: Mapped[str | None] = mapped_column(String(32))
    frequency: Mapped[str | None] = mapped_column(String(16))
    category: Mapped[str] = mapped_column(String(16), default="FINANCIAL")
    confidence: Mapped[float] = mapped_column(Float, default=0.0)
    method: Mapped[str] = mapped_column(String(8), default="REGEX")
    status: Mapped[str] = mapped_column(String(16), default="PROPOSED")
    issues_json: Mapped[str] = mapped_column(Text, default="[]")
    page: Mapped[int | None] = mapped_column(Integer)
    quote: Mapped[str | None] = mapped_column(String(500))
    reviewed_by: Mapped[str | None] = mapped_column(String(255))
    review_note: Mapped[str | None] = mapped_column(String(2000))
    created_at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)


class StatementValue(Base):
    """One line item for one entity and period. A newer value for the same key supersedes the old one."""

    __tablename__ = "dps_statement_value"
    __table_args__ = (Index("ix_dps_value_lookup", "cp_id", "key", "as_of_date", "superseded"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    cp_id: Mapped[str] = mapped_column(String(64))  # CP id or obligor id (entity key)
    key: Mapped[str] = mapped_column(String(64))
    value: Mapped[float] = mapped_column(Float)
    unit: Mapped[str] = mapped_column(String(32))
    currency: Mapped[str | None] = mapped_column(String(3))
    as_of_date: Mapped[date] = mapped_column(Date)
    derived: Mapped[bool] = mapped_column(Boolean, default=False)
    source_type: Mapped[str] = mapped_column(String(16))  # DOCUMENT | USER
    document_id: Mapped[str | None] = mapped_column(String(36))
    page: Mapped[int | None] = mapped_column(Integer)
    cell: Mapped[str | None] = mapped_column(String(64))
    note: Mapped[str | None] = mapped_column(String(2000))
    entered_by: Mapped[str | None] = mapped_column(String(255))
    superseded: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)


class EventOutbox(Base):
    """Events for Core (C5 data-events), sent by a worker with retries (transactional outbox)."""

    __tablename__ = "dps_event_outbox"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    type: Mapped[str] = mapped_column(String(32))
    payload_json: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)
    sent_at: Mapped[datetime | None] = mapped_column(UtcDateTime())
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    last_error: Mapped[str | None] = mapped_column(Text)


class AuditEvent(Base):
    __tablename__ = "dps_audit_event"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    actor: Mapped[str] = mapped_column(String(255))
    action: Mapped[str] = mapped_column(String(64))
    target_type: Mapped[str] = mapped_column(String(32))
    target_id: Mapped[str] = mapped_column(String(64))
    detail_json: Mapped[str] = mapped_column(Text, default="{}")
    at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)


class FlowState(Base):
    """Saved CrewAI Flow state (backing store for the SQL FlowPersistence, R1)."""

    __tablename__ = "dps_flow_state"
    __table_args__ = (Index("ix_dps_flow_state_uuid", "flow_uuid", "saved_at"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    flow_uuid: Mapped[str] = mapped_column(String(64))
    method_name: Mapped[str] = mapped_column(String(64))
    state_json: Mapped[str] = mapped_column(Text)
    pending_feedback_json: Mapped[str | None] = mapped_column(Text)
    saved_at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)
