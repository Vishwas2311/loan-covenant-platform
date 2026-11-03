"""CrewAI ``FlowPersistence`` on the ``dps_flow_state`` table (SQL Server in production, SQLite in tests).

``kickoff(inputs={"id": state_id})`` reloads the latest saved state through ``load_state``, which is how a
job held for review continues after the reviewer acts (03-flows-design.md §4 "Resume").
"""

from __future__ import annotations

import json
from typing import Any

from crewai.flow.persistence.base import FlowPersistence
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from dps.models import FlowState


class SqlFlowPersistence(FlowPersistence):
    model_config = ConfigDict(arbitrary_types_allowed=True)

    persistence_type: str = "SqlFlowPersistence"
    session_factory: Any = Field(default=None, exclude=True)

    def __init__(self, session_factory: sessionmaker[Session], **data: Any) -> None:
        super().__init__(session_factory=session_factory, **data)

    def init_db(self) -> None:
        """The table is created by Alembic (or ``create_schema`` in tests); nothing to do."""

    def save_state(self, flow_uuid: str, method_name: str, state_data: dict[str, Any] | BaseModel) -> None:
        data = state_data.model_dump(mode="json") if isinstance(state_data, BaseModel) else state_data
        with self.session_factory() as db:
            db.add(FlowState(flow_uuid=flow_uuid, method_name=method_name,
                             state_json=json.dumps(data, default=str)))
            db.commit()

    def load_state(self, flow_uuid: str) -> dict[str, Any] | None:
        with self.session_factory() as db:
            row = db.scalars(select(FlowState).where(FlowState.flow_uuid == flow_uuid)
                             .order_by(FlowState.saved_at.desc(), FlowState.id.desc()).limit(1)).first()
            return json.loads(row.state_json) if row else None
