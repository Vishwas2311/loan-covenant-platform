"""Per-CP access, delegated to Covenant Core so both services apply one rule.

Core's ``GET /api/v1/credit-proposals/{cpId}`` is guarded by ``CpAccessGuard``: 200 means the caller
may see the CP, 404 means not. DPS forwards the caller's identity and asks. ADMIN and SERVICE
bypass; AUDITOR and SUPPORT may read any CP but change none, as in Core (support's own repairs are the
role-gated job endpoints).
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Protocol

import httpx

from dps.auth import ADMIN, AUDITOR, ROLES_HEADER, SERVICE, SUPPORT, USER_HEADER, Principal
from dps.errors import ApiError

log = logging.getLogger(__name__)


class CpAccessChecker(Protocol):
    def allowed(self, principal: Principal, cp_id: str, read_only: bool) -> bool: ...


def has_bypass(principal: Principal, read_only: bool) -> bool:
    if principal.has_any(ADMIN, SERVICE):
        return True
    return read_only and principal.has_any(AUDITOR, SUPPORT)


class AccessCheckUnavailable(Exception):
    """Core could not answer (time-out, connection error, 5xx): neither a yes nor a no."""


class CoreCpAccessChecker:
    """Asks Core, and remembers a "yes" for ``cache_seconds`` per (user, roles, CP, read/write).

    Every DPS call on a CP needs this answer, so without the cache each one also costs a Core request
    (review BUG-07). Only grants are kept, so a revoked CP is refused again within ``cache_seconds``.
    """

    MAX_ENTRIES = 20_000

    def __init__(self, base_url: str, timeout: float, client: httpx.Client | None = None,
                 cache_seconds: float = 30.0) -> None:
        self._client = client or httpx.Client(base_url=base_url, timeout=timeout)
        self._cache_seconds = cache_seconds
        self._granted: dict[tuple, float] = {}
        self._lock = threading.Lock()

    def allowed(self, principal: Principal, cp_id: str, read_only: bool) -> bool:
        if not cp_id or not cp_id.strip():
            return False
        if has_bypass(principal, read_only):
            return True
        key = (principal.user, tuple(sorted(principal.roles)), cp_id, read_only)
        now = time.monotonic()
        with self._lock:
            until = self._granted.get(key)
        if until is not None and until > now:
            return True
        headers = ({"Authorization": f"Bearer {principal.token}"} if principal.token
                   else {USER_HEADER: principal.user, ROLES_HEADER: ",".join(sorted(principal.roles))})
        safe_cp = cp_id.replace("\n", "_")
        try:
            resp = self._client.get(f"/api/v1/credit-proposals/{cp_id}", headers=headers)
        except httpx.HTTPError as exc:
            log.warning("Core ownership check failed for %s: %s", safe_cp, type(exc).__name__)
            raise AccessCheckUnavailable from exc
        if resp.status_code >= 500:
            log.warning("Core ownership check for %s answered HTTP %s", safe_cp, resp.status_code)
            raise AccessCheckUnavailable
        if resp.status_code != 200:
            return False
        with self._lock:
            if len(self._granted) >= self.MAX_ENTRIES:
                self._granted = {k: v for k, v in self._granted.items() if v > now}
                if len(self._granted) >= self.MAX_ENTRIES:
                    self._granted.clear()
            self._granted[key] = now + self._cache_seconds
        return True


def require_cp(checker: CpAccessChecker, principal: Principal, cp_id: str, read_only: bool = True) -> None:
    """Refuses with 404 (like Core) so other portfolios' CPs are not revealed; 503 when Core cannot answer."""
    try:
        ok = checker.allowed(principal, cp_id, read_only)
    except AccessCheckUnavailable as exc:
        raise ApiError(503, "ACCESS_CHECK_UNAVAILABLE",
                       "Access to this credit proposal could not be checked right now. Try again in a moment.") from exc
    if not ok:
        raise ApiError(404, "CP_NOT_FOUND", "Credit proposal not found")
