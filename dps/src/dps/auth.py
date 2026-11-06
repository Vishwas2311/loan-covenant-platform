"""Authentication: Entra ID JWT (production) or trusted headers (dev/test only)."""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

import jwt
from fastapi import Request

from dps.errors import ApiError
from dps.settings import AuthMode, Settings

USER_HEADER = "X-User"
ROLES_HEADER = "X-Roles"

log = logging.getLogger(__name__)

ADMIN, APPROVER, AUDITOR, AUTHOR, RM, SERVICE, SUPPORT = (
    "ADMIN", "APPROVER", "AUDITOR", "AUTHOR", "RM", "SERVICE", "SUPPORT")


@dataclass(frozen=True)
class Principal:
    user: str
    roles: frozenset[str] = field(default_factory=frozenset)
    token: str | None = None  # raw bearer token, forwarded to Core for ownership checks

    def has_any(self, *roles: str) -> bool:
        return any(r in self.roles for r in roles)


def _unauthorized(message: str = "Authentication required") -> ApiError:
    return ApiError(401, "UNAUTHORIZED", message)


class Authenticator:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._jwks: jwt.PyJWKClient | None = None
        if settings.auth_mode == AuthMode.DEV_HEADER and settings.env.lower() == "prod":
            raise RuntimeError("DPS_AUTH_MODE=DEV_HEADER is not allowed with DPS_ENV=prod; use JWT")
        # Entra ID signs every tenant's tokens with the same keys: without issuer and audience checks a
        # token Microsoft issued for any other tenant or app would be accepted.
        if settings.auth_mode == AuthMode.JWT and not (settings.jwt_issuer and settings.jwt_audience):
            raise RuntimeError("DPS_AUTH_MODE=JWT needs DPS_JWT_ISSUER and DPS_JWT_AUDIENCE")
        if settings.auth_mode == AuthMode.JWT and not settings.jwt_public_key_pem:
            if not settings.jwt_jwks_url:
                raise RuntimeError("DPS_AUTH_MODE=JWT needs DPS_JWT_JWKS_URL or DPS_JWT_PUBLIC_KEY_PEM")
            self._jwks = jwt.PyJWKClient(settings.jwt_jwks_url)

    def authenticate(self, request: Request) -> Principal:
        if self.settings.auth_mode == AuthMode.DEV_HEADER:
            return self._from_headers(request)
        return self._from_jwt(request)

    @staticmethod
    def _from_headers(request: Request) -> Principal:
        user = (request.headers.get(USER_HEADER) or "").strip()
        if not user:
            raise _unauthorized()
        raw = request.headers.get(ROLES_HEADER) or RM
        roles = frozenset(r.strip().upper() for r in raw.split(",") if r.strip())
        return Principal(user=user, roles=roles or frozenset({RM}))

    def _from_jwt(self, request: Request) -> Principal:
        header = request.headers.get("Authorization") or ""
        if not header.lower().startswith("bearer "):
            raise _unauthorized()
        token = header[7:].strip()
        s = self.settings
        try:
            key = (s.jwt_public_key_pem if s.jwt_public_key_pem
                   else self._jwks.get_signing_key_from_jwt(token).key)  # type: ignore[union-attr]
            claims = jwt.decode(
                token, key, algorithms=["RS256"],
                audience=s.jwt_audience or None,
                issuer=s.jwt_issuer or None,
                leeway=s.jwt_leeway_seconds,
                options={"require": ["exp", "iat"], "verify_aud": bool(s.jwt_audience)},
            )
        except jwt.PyJWTError as exc:
            # The reason only (expired, wrong audience, key fetch failed...), never the token itself.
            log.warning("Sign-in refused: %s", type(exc).__name__)
            raise _unauthorized("Invalid token") from exc
        user = claims.get(s.jwt_principal_claim) or claims.get("sub")
        if not user:
            raise _unauthorized("Token has no user")
        roles = claims.get(s.jwt_roles_claim) or []
        if isinstance(roles, str):
            roles = [roles]
        return Principal(user=str(user), roles=frozenset(str(r).strip().upper() for r in roles if r),
                         token=token)


def require_roles(principal: Principal, *roles: str) -> None:
    if not principal.has_any(*roles):
        raise ApiError(403, "FORBIDDEN", "Missing required role")
