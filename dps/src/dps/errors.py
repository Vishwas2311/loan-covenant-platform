"""Error envelope matching Covenant Core: ``{code, message, details, timestamp}``."""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse


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


def install(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(status_code=exc.status, content=envelope(exc.code, exc.message, exc.details))

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        errors = [{"loc": list(e.get("loc", [])), "msg": e.get("msg", "")} for e in exc.errors()]
        return JSONResponse(status_code=400, content=envelope("VALIDATION_FAILED", "Invalid request",
                                                              {"errors": errors}))
