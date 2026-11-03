"""Application factory. Run: ``uvicorn --factory dps.main:create_app --port 9400``."""

from __future__ import annotations

import logging
import uuid

from fastapi import FastAPI, Request

from dps import __version__, ask, errors
from dps.access import CoreCpAccessChecker, CpAccessChecker
from dps.advisor import Advisor, crewai_completion
from dps.api import router
from dps.auth import Authenticator
from dps.db import Base, Database
from dps.settings import Settings, get_settings
from dps.storage import FileStore

CORRELATION_HEADER = "X-Correlation-Id"


def create_app(settings: Settings | None = None, *, cp_checker: CpAccessChecker | None = None,
               create_schema: bool = False, advisor: Advisor | None = None,
               core_fetch: ask.Fetch | None = None) -> FastAPI:
    settings = settings or get_settings()
    app = FastAPI(title="Document Processing Service", version=__version__)
    app.state.settings = settings
    app.state.db = Database(settings.database_url)
    app.state.store = FileStore(settings.storage_dir)
    app.state.authenticator = Authenticator(settings)
    app.state.cp_checker = cp_checker or CoreCpAccessChecker(settings.core_base_url, settings.core_timeout_seconds)
    completion = (crewai_completion(settings.ai_model, settings.ai_temperature)
                  if settings.ai_model and not advisor else None)
    app.state.advisor = advisor or Advisor(completion)
    app.state.core_fetch = core_fetch or ask.core_fetch(settings.core_base_url, settings.core_timeout_seconds)
    if create_schema:  # tests / local only; production uses Alembic migrations
        Base.metadata.create_all(app.state.db.engine)

    @app.middleware("http")
    async def correlation_id(request: Request, call_next):
        cid = request.headers.get(CORRELATION_HEADER) or str(uuid.uuid4())
        request.state.correlation_id = cid
        response = await call_next(request)
        response.headers[CORRELATION_HEADER] = cid
        return response

    errors.install(app)
    app.include_router(router)
    logging.getLogger("dps").info("DPS %s started (auth=%s)", __version__, settings.auth_mode.value)
    return app

