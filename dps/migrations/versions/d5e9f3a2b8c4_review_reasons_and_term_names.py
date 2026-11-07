"""Why a statement waits for review, and each proposed covenant's own name

Revision ID: d5e9f3a2b8c4
Revises: c4d8e2f1a7b3
Create Date: 2026-11-07 10:00:00

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'd5e9f3a2b8c4'
down_revision: Union[str, Sequence[str], None] = 'c4d8e2f1a7b3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # {"issues": [...], "unitScale": "lakh"}: shown on the document so the reviewer knows what to check.
    op.add_column('dps_document', sa.Column('review_json', sa.Text(), nullable=True))
    # "Consolidated Senior Secured Leverage Ratio": the name shown to people and given to a custom covenant.
    op.add_column('dps_extracted_term', sa.Column('name', sa.String(length=200), nullable=True))


def downgrade() -> None:
    op.drop_column('dps_extracted_term', 'name')
    op.drop_column('dps_document', 'review_json')
