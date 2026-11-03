from __future__ import annotations

import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from dps.auth import Principal
from dps.db import Base, make_engine
from dps.main import create_app
from dps.settings import AuthMode, Settings


class FakeChecker:
    """Ownership map: user -> CPs. ADMIN/SUPPORT/SERVICE bypass, AUDITOR reads (same rule as Core)."""

    def __init__(self, owners: dict[str, set[str]]) -> None:
        self.owners = owners
        self.calls: list[tuple[str, str, bool]] = []

    def allowed(self, principal: Principal, cp_id: str, read_only: bool) -> bool:
        from dps.access import has_bypass
        self.calls.append((principal.user, cp_id, read_only))
        return has_bypass(principal, read_only) or cp_id in self.owners.get(principal.user, set())


# Set DPS_TEST_DATABASE_URL (e.g. mssql+pymssql://sa:...@localhost:1433/dps_test) to run the suite on
# SQL Server instead of a throwaway SQLite file; each test then starts from an emptied schema.
TEST_DATABASE_URL = os.environ.get("DPS_TEST_DATABASE_URL", "")


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    url = TEST_DATABASE_URL or f"sqlite:///{tmp_path / 'dps.db'}"
    if TEST_DATABASE_URL:
        engine = make_engine(url)
        Base.metadata.drop_all(engine)
        engine.dispose()
    return Settings(database_url=url, storage_dir=tmp_path / "store",
                    auth_mode=AuthMode.DEV_HEADER, max_upload_mb=1, core_data_source_id="ds-dps")


@pytest.fixture
def checker() -> FakeChecker:
    return FakeChecker({"rm-anil": {"CP-1", "CP-2"}, "approver-1": {"CP-1"}, "rm-other": {"CP-9"}})


@pytest.fixture
def app(settings: Settings, checker: FakeChecker):
    return create_app(settings, cp_checker=checker, create_schema=True)


@pytest.fixture
def client(app) -> TestClient:
    return TestClient(app)


def h(user: str = "rm-anil", roles: str = "RM") -> dict[str, str]:
    return {"X-User": user, "X-Roles": roles}
