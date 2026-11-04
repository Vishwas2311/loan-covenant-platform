"""Per-CP access, delegated to Covenant Core so both services apply one rule.

Core's ``GET /api/v1/credit-proposals/{cpId}`` is guarded by ``CpAccessGuard``: 200 means the caller
may see the CP, 404 means not. DPS forwards the caller's identity and asks. ADMIN and SERVICE
bypass; AUDITOR and SUPPORT may read any CP but change none, as in Core (support's own repairs are the
role-gated job endpoints).
"""

from __future__ import annotations

import logging
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


class CoreCpAccessChecker:
    def __init__(self, base_url: str, timeout: float, client: httpx.Client | None = None) -> None:
        self._client = client or httpx.Client(base_url=base_url, timeout=timeout)

    def allowed(self, principal: Principal, cp_id: str, read_only: bool) -> bool:
        if not cp_id or not cp_id.strip():
            return False
        if has_bypass(principal, read_only):
            return True
        headers = ({"Authorization": f"Bearer {principal.token}"} if principal.token
                   else {USER_HEADER: principal.user, ROLES_HEADER: ",".join(sorted(principal.roles))})
        try:
            resp = self._client.get(f"/api/v1/credit-proposals/{cp_id}", headers=headers)
        except httpx.HTTPError as exc:
            log.warning("Core ownership check failed for %s: %s", cp_id.replace("\n", "_"), type(exc).__name__)
            return False
        return resp.status_code == 200


def require_cp(checker: CpAccessChecker, principal: Principal, cp_id: str, read_only: bool = True) -> None:
    """Refuses with 404 (like Core) so other portfolios' CPs are not revealed."""
    if not checker.allowed(principal, cp_id, read_only):
        raise ApiError(404, "CP_NOT_FOUND", "Credit proposal not found")
