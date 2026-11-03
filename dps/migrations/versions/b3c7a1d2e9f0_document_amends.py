"""C7: link an amendment to the agreement it amends

Revision ID: b3c7a1d2e9f0
Revises: fe6380fbb355
Create Date: 2026-11-04 10:00:00

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'b3c7a1d2e9f0'
down_revision: Union[str, Sequence[str], None] = 'fe6380fbb355'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('dps_document', sa.Column('amends_document_id', sa.String(length=36), nullable=True))


def downgrade() -> None:
    op.drop_column('dps_document', 'amends_document_id')
