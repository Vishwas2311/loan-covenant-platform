"""SQLAlchemy engine and session handling."""

from __future__ import annotations

from collections.abc import Iterator

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker


class Base(DeclarativeBase):
    pass


def make_engine(url: str) -> Engine:
    kwargs: dict = {"pool_pre_ping": True}
    if url.startswith("sqlite"):
        kwargs["connect_args"] = {"check_same_thread": False}
    return create_engine(url, **kwargs)


def require_snapshot_isolation(engine: Engine) -> None:
    """On SQL Server, refuses to start unless READ_COMMITTED_SNAPSHOT is on (as Core does, review GAP-11).

    Without it, readers wait on writers and concurrent uploads time out or deadlock. Other databases
    (SQLite in tests and the local stack) are not checked.
    """
    if engine.dialect.name != "mssql":
        return
    with engine.connect() as conn:
        on = conn.execute(text(
            "SELECT is_read_committed_snapshot_on FROM sys.databases WHERE name = DB_NAME()")).scalar()
        name = conn.execute(text("SELECT DB_NAME()")).scalar()
    if not on:
        raise RuntimeError(
            f"READ_COMMITTED_SNAPSHOT is off for database {name}. Ask a DBA to run: "
            f"ALTER DATABASE [{name}] SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE; then start DPS again.")


class Database:
    """Holds the engine and session factory for one app instance."""

    def __init__(self, url: str) -> None:
        self.engine = make_engine(url)
        self.session_factory = sessionmaker(bind=self.engine, expire_on_commit=False)

    def session(self) -> Iterator[Session]:
        with self.session_factory() as s:
            yield s
