"""GAP-05: tokens and cost of model calls

Revision ID: c4d8e2f1a7b3
Revises: b3c7a1d2e9f0
Create Date: 2026-11-05 16:00:00

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'c4d8e2f1a7b3'
down_revision: Union[str, Sequence[str], None] = 'b3c7a1d2e9f0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'dps_ai_usage',
        sa.Column('id', sa.String(length=36), primary_key=True),
        sa.Column('at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('purpose', sa.String(length=24), nullable=False),
        sa.Column('user', sa.String(length=255), nullable=False),
        sa.Column('cp_id', sa.String(length=64), nullable=True),
        sa.Column('document_id', sa.String(length=36), nullable=True),
        sa.Column('job_id', sa.String(length=36), nullable=True),
        sa.Column('model', sa.String(length=128), nullable=False),
        sa.Column('calls', sa.Integer(), nullable=False),
        sa.Column('prompt_tokens', sa.Integer(), nullable=False),
        sa.Column('completion_tokens', sa.Integer(), nullable=False),
        sa.Column('cost', sa.Float(), nullable=True),
    )
    op.create_index('ix_dps_ai_usage_at', 'dps_ai_usage', ['at'])


def downgrade() -> None:
    op.drop_index('ix_dps_ai_usage_at', table_name='dps_ai_usage')
    op.drop_table('dps_ai_usage')
