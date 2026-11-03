"""Configuration from environment variables (prefix ``DPS_``)."""

from __future__ import annotations

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
    # Bearer token for DPS → Core calls (C4 reads, C5 events). Empty: dev headers X-User/X-Roles=SERVICE.
    core_service_token: str = ""

    # Flow A: proposals below this confidence, or with any issue, are held for review.
    review_confidence: float = 0.8
    # Workers
    worker_id: str = "dps-worker"
    worker_pool: str = "STANDARD"  # STANDARD | HEAVY
    worker_lease_seconds: int = 900
    worker_max_attempts: int = 3
    ocr_enabled: bool = True

    # Advisory AI (C3 suggestions, statement narrative). Empty: rules only. Example: azure/gpt-4o
    # with AZURE_API_KEY, AZURE_API_BASE and AZURE_API_VERSION set for LiteLLM.
    ai_model: str = ""


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
