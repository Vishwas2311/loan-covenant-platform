"""DPS → Covenant Core calls made by the flows and workers as the SERVICE identity.

C4 reads: covenant library (``GET /api/v1/covenants``) and formula validation
(``POST /api/v1/formulas/validate``). C5: ``POST /api/v1/data-events``.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

import httpx

from dps.auth import ROLES_HEADER, SERVICE, USER_HEADER

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class CoreMaster:
    id: str
    name: str
    category: str
    assessment_type: str
    referenced_measures: tuple[str, ...]


class CoreUnavailable(Exception):
    """Core could not be reached or answered 5xx; the caller retries later."""


class CoreClient:
    def __init__(self, base_url: str, timeout: float, service_token: str = "",
                 client: httpx.Client | None = None) -> None:
        headers = ({"Authorization": f"Bearer {service_token}"} if service_token
                   else {USER_HEADER: "dps-service", ROLES_HEADER: SERVICE})
        self._client = client or httpx.Client(base_url=base_url, timeout=timeout)
        self._headers = headers

    def _request(self, method: str, path: str, **kw) -> httpx.Response:
        try:
            resp = self._client.request(method, path, headers=self._headers, **kw)
        except httpx.HTTPError as exc:
            raise CoreUnavailable(f"{method} {path}: {type(exc).__name__}") from exc
        if resp.status_code >= 500:
            raise CoreUnavailable(f"{method} {path}: HTTP {resp.status_code}")
        return resp

    def active_masters(self) -> list[CoreMaster]:
        """All active library covenants (paged read of Core's search endpoint)."""
        out: list[CoreMaster] = []
        page = 0
        while True:
            resp = self._request("GET", "/api/v1/covenants", params={"active": "true", "size": 200, "page": page})
            resp.raise_for_status()
            body = resp.json()
            for c in body.get("content", []):
                if c.get("custom"):
                    continue  # CP-scoped custom covenants are not library entries
                out.append(CoreMaster(str(c["id"]), c.get("name") or "", c.get("category") or "",
                                      c.get("assessmentType") or "AUTO", tuple(c.get("referencedMeasures") or ())))
            if body.get("last", True):
                return out
            page += 1

    def validate_formula(self, expression: str) -> list[str]:
        """Core's error messages for the expression; empty when valid."""
        resp = self._request("POST", "/api/v1/formulas/validate",
                             json={"expression": expression, "requireBoolean": True})
        resp.raise_for_status()
        body = resp.json()
        if body.get("valid"):
            return []
        return [e.get("message") or e.get("code") or "invalid" for e in body.get("errors") or []] or ["invalid"]

    def send_data_event(self, payload: dict) -> dict:
        """C5. 202 (or 200 for a duplicate) is success; 4xx is permanent and raised as HTTPStatusError."""
        resp = self._request("POST", "/api/v1/data-events", json=payload)
        resp.raise_for_status()
        return resp.json() if resp.content else {}
