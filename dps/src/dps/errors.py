"""Error envelope matching Covenant Core: ``{code, message, details, timestamp}``."""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import OperationalError


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, details: dict | None = None) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.details = details or {}


def envelope(code: str, message: str, details: dict | None = None) -> dict:
    return {
        "code": code,
        "message": message,
        "details": details or {},
        "timestamp": datetime.now(UTC).isoformat(),
    }


def not_found(what: str = "Resource") -> ApiError:
    return ApiError(404, "NOT_FOUND", f"{what} not found")


def is_lock_conflict(exc: BaseException) -> bool:
    """A transaction lost to a concurrent one: SQL Server deadlock (1205) or lock timeout (1222), SQLite lock."""
    text = str(getattr(exc, "orig", exc))
    return "1205" in text or "1222" in text or "deadlock" in text.lower() or "database is locked" in text


def install(app: FastAPI) -> None:
    @app.exception_handler(OperationalError)
    async def _busy(_: Request, exc: OperationalError) -> JSONResponse:
        if is_lock_conflict(exc):
            return JSONResponse(status_code=503, headers={"Retry-After": "2"},
                                content=envelope("BUSY_RETRY", "The service is busy. Try again in a moment."))
        raise exc

    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(status_code=exc.status, content=envelope(exc.code, exc.message, exc.details))

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        errors = [{"loc": list(e.get("loc", [])), "msg": e.get("msg", "")} for e in exc.errors()]
        return JSONResponse(status_code=400, content=envelope("VALIDATION_FAILED", "Invalid request",
                                                              {"errors": errors}))
