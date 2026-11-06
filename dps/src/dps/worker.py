"""Background workers (03-flows-design.md §4). Run: ``python -m dps.worker`` (``DPS_WORKER_POOL=HEAVY`` for Docling).

Each loop: send pending C5 events, then claim one job and run its flow. Transient failures (Core down,
file store hiccup) put the job back in the queue until ``worker_max_attempts``; deterministic outcomes
(FAILED, NEEDS_REVIEW) end the attempt.
"""

from __future__ import annotations

import json
import logging
import signal
import threading
import time
from collections.abc import Callable

import httpx
from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from dps import jobs
from dps.core_client import CoreClient, CoreUnavailable
from dps.db import Database, require_snapshot_isolation
from dps.models import Document, EventOutbox, Job, utcnow
from dps.settings import Settings, get_settings
from dps.storage import FileStore

log = logging.getLogger(__name__)

# flow name → callable(job, settings) -> outcome ("PUBLISHED" | "REPORTED" | "NEEDS_REVIEW" | "FAILED" | "HEAVY")
FlowRunner = Callable[[Job], tuple[str, str, str]]  # (outcome, flow_state_id, error)

OUTCOME_TO_STATUS = {"PUBLISHED": jobs.DONE, "REPORTED": jobs.DONE, "NEEDS_REVIEW": jobs.NEEDS_REVIEW,
                     "FAILED": jobs.FAILED}


class Worker:
    def __init__(self, settings: Settings, session_factory: sessionmaker[Session], core: CoreClient,
                 runners: dict[str, FlowRunner]) -> None:
        self.settings = settings
        self.session_factory = session_factory
        self.core = core
        self.runners = runners

    def run_once(self) -> bool:
        """Sends events, then processes at most one job. Returns True when a job was processed."""
        self.send_events()
        with self.session_factory() as db:
            job = jobs.claim(db, self.settings.worker_id, self.settings.worker_pool,
                             self.settings.worker_lease_seconds, self.settings.worker_max_attempts)
            if job is None:
                return False
            self._process(db, job)
            return True

    def _process(self, db: Session, job: Job) -> None:
        runner = self.runners.get(job.flow)
        if runner is None:
            jobs.finish(db, job, jobs.FAILED, f"No flow registered for {job.flow}")
            return
        jobs.record_step(db, job, "START", attempt=job.attempts, worker=self.settings.worker_id)
        started = time.monotonic()
        stop = self._start_heartbeat(job.id)
        try:
            outcome, state_id, error = runner(job)
        except (CoreUnavailable, httpx.HTTPError, OSError) as ex:
            log.warning("Job %s attempt %d failed: %s", job.id, job.attempts, type(ex).__name__)
            db.refresh(job)
            jobs.record_step(db, job, "RETRY", error=str(ex)[:500])
            jobs.release_for_retry(db, job, str(ex)[:2000], self.settings.worker_max_attempts)
            self._sync_document(db, job)
            return
        except Exception as ex:  # a bug: fail the job, keep the worker alive
            log.exception("Job %s crashed", job.id)
            db.refresh(job)
            jobs.finish(db, job, jobs.FAILED, f"{type(ex).__name__}: {str(ex)[:1900]}")
            self._sync_document(db, job)
            return
        finally:
            stop.set()
        db.refresh(job)
        job.flow_state_id = state_id
        jobs.record_step(db, job, "END", outcome=outcome, seconds=round(time.monotonic() - started, 2))
        if outcome == "HEAVY":
            jobs.move_to_pool(db, job, "HEAVY")
            return
        jobs.finish(db, job, OUTCOME_TO_STATUS.get(outcome, jobs.FAILED), error or None)

    def _start_heartbeat(self, job_id: str) -> threading.Event:
        """Renews the job's lease every third of its length until the returned event is set."""
        stop = threading.Event()
        lease = self.settings.worker_lease_seconds

        def beat() -> None:
            while not stop.wait(max(1.0, lease / 3)):
                try:
                    with self.session_factory() as db:
                        if not jobs.renew_lease(db, job_id, self.settings.worker_id, lease):
                            log.warning("Job %s: lease lost to another worker", job_id)
                            return
                except Exception:  # noqa: BLE001 - a missed beat only shortens the lease
                    log.warning("Job %s: lease renewal failed", job_id, exc_info=True)

        threading.Thread(target=beat, name=f"lease-{job_id[:8]}", daemon=True).start()
        return stop

    def _sync_document(self, db: Session, job: Job) -> None:
        if job.status == jobs.FAILED:
            doc = db.get(Document, job.document_id)
            if doc is not None:
                doc.status = "FAILED"
                db.commit()

    def send_events(self, limit: int = 20) -> int:
        """C5 transactional outbox: delivers unsent events in order; stops at the first transient error."""
        sent = 0
        with self.session_factory() as db:
            pending = db.scalars(select(EventOutbox).where(EventOutbox.sent_at.is_(None),
                                                           EventOutbox.attempts < 50)
                                 .order_by(EventOutbox.created_at).limit(limit)).all()
            for event in pending:
                event.attempts += 1
                payload = json.loads(event.payload_json)
                if not payload.get("dataSourceId"):
                    payload["dataSourceId"] = self.settings.core_data_source_id
                try:
                    self.core.send_data_event(payload)
                except CoreUnavailable as ex:
                    event.last_error = str(ex)[:2000]
                    db.commit()
                    break
                except httpx.HTTPStatusError as ex:  # 4xx: will not succeed on retry
                    event.last_error = f"HTTP {ex.response.status_code}: {ex.response.text[:500]}"
                    event.attempts = 50
                    db.commit()
                    continue
                event.sent_at = utcnow()
                db.commit()
                sent += 1
        return sent


def build_runners(settings: Settings, database: Database, store: FileStore, core: CoreClient) -> dict[str, FlowRunner]:
    """Wires the flows with their dependencies (imported here so the API process never loads CrewAI)."""
    from dps.extraction.statement_pdf import read_pdf_statement
    from dps.flows.intake import AgreementIntakeFlow, IntakeDeps
    from dps.flows.statement import StatementDeps, StatementProcessingFlow
    from dps.parsing import DoclingEngine, ParserEngine, RapidOcrBackend

    heavy = settings.worker_pool == "HEAVY"
    ocr = RapidOcrBackend() if settings.ocr_enabled else None
    parser = DoclingEngine() if heavy else ParserEngine(ocr)

    def read_file(doc: Document) -> bytes:
        return store.path(doc.storage_path).read_bytes()

    def run_intake(job: Job) -> tuple[str, str, str]:
        deps = IntakeDeps(database.session_factory, read_file, parser, core, settings.review_confidence, heavy)
        return _kickoff(AgreementIntakeFlow(deps), job)

    second_reader = None
    if settings.statement_ai_check and settings.ai_model:  # pragma: no cover - live model
        from dps.advisor import crewai_completion
        from dps.extraction.statement_ai import check

        completion = crewai_completion(settings.ai_model, settings.ai_temperature, settings.ai_timeout_seconds)

        def second_reader(text, values):
            return check(text, values, completion)

    def run_statement(job: Job) -> tuple[str, str, str]:
        deps = StatementDeps(database.session_factory, read_file, parser, settings.core_data_source_id, heavy,
                             lambda data: read_pdf_statement(data, ocr), second_reader)
        return _kickoff(StatementProcessingFlow(deps), job)

    return {"AGREEMENT_INTAKE": run_intake, "STATEMENT_PROCESSING": run_statement}


def _kickoff(flow, job: Job) -> tuple[str, str, str]:
    inputs = {"job_id": job.id, "document_id": job.document_id}
    if job.flow_state_id:
        inputs["id"] = job.flow_state_id  # resume the saved run (after review or a heavy hand-off)
    flow.kickoff(inputs=inputs)
    return flow.state.outcome, flow.state.id, flow.state.error


def main() -> None:  # pragma: no cover - process entry point
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = get_settings()
    database = Database(settings.database_url)
    require_snapshot_isolation(database.engine)
    core = CoreClient.from_settings(settings)
    worker = Worker(settings, database.session_factory, core,
                    build_runners(settings, database, FileStore(settings.storage_dir), core))
    stop = {"flag": False}
    signal.signal(signal.SIGTERM, lambda *_: stop.update(flag=True))
    log.info("DPS worker %s started (pool=%s)", settings.worker_id, settings.worker_pool)
    while not stop["flag"]:
        if not worker.run_once():
            time.sleep(2)


if __name__ == "__main__":  # pragma: no cover
    main()
