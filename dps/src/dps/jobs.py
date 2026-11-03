"""Job queue on the ``dps_job`` table (03-flows-design.md §4).

Claiming is a conditional UPDATE (compare-and-set on status + lease), so it is safe with many workers on
SQL Server and on SQLite alike. A RUNNING job whose lease expired (worker died) is claimable again.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta

from sqlalchemy import or_, select, update
from sqlalchemy.orm import Session

from dps.models import Job, utcnow

QUEUED, RUNNING, NEEDS_REVIEW, DONE, FAILED = "QUEUED", "RUNNING", "NEEDS_REVIEW", "DONE", "FAILED"


def _aware(value: datetime | None) -> datetime | None:
    if value is not None and value.tzinfo is None:
        return value.replace(tzinfo=UTC)  # SQLite returns naive datetimes
    return value


def claim(db: Session, worker_id: str, pool: str, lease_seconds: int, max_attempts: int) -> Job | None:
    """Claims the oldest runnable job of ``pool`` for ``worker_id`` or returns None."""
    now = utcnow()
    candidates = db.scalars(
        select(Job.id)
        .where(Job.pool == pool, Job.attempts < max_attempts,
               or_(Job.status == QUEUED, (Job.status == RUNNING) & (Job.locked_until < now)))
        .order_by(Job.created_at)
        .limit(5)).all()
    for job_id in candidates:
        current = db.get(Job, job_id)
        if current is None:
            continue
        result = db.execute(
            update(Job)
            .where(Job.id == job_id, Job.status == current.status, Job.attempts == current.attempts)
            .values(status=RUNNING, locked_by=worker_id, locked_until=now + timedelta(seconds=lease_seconds),
                    attempts=Job.attempts + 1, updated_at=now)
            .execution_options(synchronize_session=False))
        db.commit()
        if result.rowcount == 1:
            job = db.get(Job, job_id)
            db.refresh(job)
            return job
    return None


def record_step(db: Session, job: Job, step: str, **detail) -> None:
    steps = json.loads(job.steps_json or "[]")
    steps.append({"step": step, "at": utcnow().isoformat(), **detail})
    job.steps_json, job.current_step, job.updated_at = json.dumps(steps, default=str), step, utcnow()
    db.commit()


def finish(db: Session, job: Job, status: str, error: str | None = None) -> None:
    job.status, job.error, job.locked_by, job.locked_until, job.updated_at = status, error, None, None, utcnow()
    db.commit()


def release_for_retry(db: Session, job: Job, error: str, max_attempts: int) -> None:
    """Transient failure: back to QUEUED until attempts run out, then FAILED."""
    status = FAILED if job.attempts >= max_attempts else QUEUED
    finish(db, job, status, error)


def move_to_pool(db: Session, job: Job, pool: str) -> None:
    """Hands the job to another pool (fast parse not good enough → HEAVY) without using up an attempt."""
    job.pool, job.status = pool, QUEUED
    job.attempts = max(0, job.attempts - 1)
    job.locked_by, job.locked_until, job.updated_at = None, None, utcnow()
    db.commit()


def lease_expired(job: Job) -> bool:
    until = _aware(job.locked_until)
    return until is None or until < utcnow()
