"""DPS's own identity for calls to Core: an OAuth 2.0 client-credentials token, refreshed before it expires.

Entra ID access tokens last about an hour, so a fixed token in configuration stops working. Configure
``DPS_CORE_TOKEN_URL`` (e.g. ``https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token``),
``DPS_CORE_CLIENT_ID``, ``DPS_CORE_CLIENT_SECRET`` and ``DPS_CORE_SCOPE`` (``api://<api-app>/.default``).
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable

import httpx

# Refresh this long before expiry, so a token never runs out mid-request.
REFRESH_MARGIN_SECONDS = 60


class TokenError(Exception):
    """The token endpoint refused the credentials or returned no token."""


class ClientCredentialsToken:
    def __init__(self, token_url: str, client_id: str, client_secret: str, scope: str,
                 client: httpx.Client | None = None, clock: Callable[[], float] = time.monotonic) -> None:
        if not (token_url and client_id and client_secret and scope):
            raise ValueError("Client credentials need DPS_CORE_TOKEN_URL, _CLIENT_ID, _CLIENT_SECRET and _SCOPE")
        self._form = {"grant_type": "client_credentials", "client_id": client_id,
                      "client_secret": client_secret, "scope": scope}
        self._url = token_url
        self._client = client or httpx.Client(timeout=10)
        self._clock = clock
        self._lock = threading.Lock()
        self._token = ""
        self._expires_at = 0.0

    def token(self) -> str:
        with self._lock:  # one refresh even when many threads need a token at once
            if not self._token or self._clock() >= self._expires_at - REFRESH_MARGIN_SECONDS:
                self._refresh()
            return self._token

    def _refresh(self) -> None:
        try:
            resp = self._client.post(self._url, data=self._form)
        except httpx.HTTPError as exc:
            raise TokenError(f"Token endpoint unreachable: {type(exc).__name__}") from exc
        if resp.status_code != 200:
            # The body may echo the request; report only the status.
            raise TokenError(f"Token endpoint refused client credentials: HTTP {resp.status_code}")
        body = resp.json()
        if not body.get("access_token"):
            raise TokenError("Token endpoint returned no access_token")
        self._token = body["access_token"]
        self._expires_at = self._clock() + float(body.get("expires_in") or 300)
