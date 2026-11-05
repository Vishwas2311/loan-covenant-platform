"""Configuration from environment variables (prefix ``DPS_``)."""

from __future__ import annotations

import socket
from enum import Enum
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class AuthMode(str, Enum):
    JWT = "JWT"
    DEV_HEADER = "DEV_HEADER"  # dev/test only: trust X-User / X-Roles


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DPS_", env_file=".env", extra="ignore")

    database_url: str = "sqlite:///./dps.db"
    storage_dir: Path = Path("./storage")
    max_upload_mb: int = 50
    max_pages: int = 500  # FR-01: longer PDFs are refused at upload
    # Per-user calls a minute (0 switches a limit off): AI answers cost money, uploads queue work.
    ask_per_minute: int = 20
    uploads_per_minute: int = 30

    # "prod" refuses DEV_HEADER at startup; set DPS_ENV=prod in every production deployment.
    env: str = "dev"
    auth_mode: AuthMode = AuthMode.JWT
    jwt_issuer: str = ""
    jwt_audience: str = ""
    jwt_jwks_url: str = ""
    jwt_public_key_pem: str = ""  # alternative to JWKS (tests, air-gapped)
    jwt_principal_claim: str = "preferred_username"
    jwt_roles_claim: str = "roles"

    # Covenant Core, used for CP ownership checks and C5 data-events.
    core_base_url: str = "http://localhost:9310"
    core_timeout_seconds: float = 5.0
    # Data source id under which DPS is registered in Core (sent with C5 events).
    core_data_source_id: str = ""
    # DPS → Core calls (C4 reads, C5 events). Production: client credentials (token refreshed automatically,
    # wins over core_service_token). A fixed core_service_token expires; empty both: SERVICE dev headers.
    core_token_url: str = ""
    core_client_id: str = ""
    core_client_secret: str = ""
    core_scope: str = ""
    core_service_token: str = ""

    # Flow A: proposals below this confidence, or with any issue, are held for review.
    review_confidence: float = 0.8
    # Workers
    # Defaults to the host name, so scaled replicas (docker compose --scale, Kubernetes) are told apart.
    worker_id: str = socket.gethostname() or "dps-worker"
    worker_pool: str = "STANDARD"  # STANDARD | HEAVY
    # A running job's lease, renewed every third of it while the worker is alive: a worker that dies
    # gives its job back within this time, and a long job is never taken over while it still runs.
    worker_lease_seconds: int = 120
    worker_max_attempts: int = 3
    ocr_enabled: bool = True

    # Advisory AI (C3 suggestions, statement narrative). Empty: rules only. Example: azure/gpt-4o
    # with AZURE_API_KEY, AZURE_API_BASE and AZURE_API_VERSION set for LiteLLM.
    ai_model: str = ""
    # Empty: the model's default (required by newer Azure OpenAI models). Set e.g. 0 where the model allows it.
    ai_temperature: float | None = None


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
