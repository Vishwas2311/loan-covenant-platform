-- =====================================================================================
-- US-DS (onboarding journey) : obligor identity + obligor->CP mapping + entity-key level
-- -------------------------------------------------------------------------------------
-- External data often arrives at OBLIGOR grain, but covenants execute per CP_ID. This
-- migration introduces obligor identity so obligor-keyed source data can fan out to every
-- CP under that obligor:
--
--   * cov_obligor_cp_map : source of truth for the (obligor -> many CPs) relationship,
--     captured when a CP is looked up. UNIQUE(cp_id) enforces "many CPs -> one obligor";
--     indexed on obligor_id for the reverse fan-out (obligor -> its CPs).
--   * cov_credit_proposal_cache.obligor_id : a denormalized forward copy (cp -> obligor)
--     for the hot execution path, so the daily run resolves an obligor without a join.
--   * cov_data_source_table.entity_key_level : whether a table is keyed by cp_id (default,
--     unchanged behavior) or obligor_id. Existing tables default to 'cp_id' (a no-op).
-- =====================================================================================
CREATE TABLE cov_obligor_cp_map (
    id            VARCHAR(36) NOT NULL,
    obligor_id    VARCHAR(64) NOT NULL,
    cp_id         VARCHAR(64) NOT NULL,
    created_date  DATETIME    NOT NULL,
    created_by    VARCHAR(50) NOT NULL,
    modified_date DATETIME    NOT NULL,
    modified_by   VARCHAR(50) NOT NULL,
    version       VARCHAR(36) NOT NULL,
    CONSTRAINT pk_obligor_cp_map    PRIMARY KEY (id),
    CONSTRAINT uk_obligor_cp_map_cp UNIQUE (cp_id)          -- many CPs -> one obligor
);
CREATE INDEX ix_obligor_cp_map_obligor ON cov_obligor_cp_map(obligor_id);

-- Denormalized forward copy of the obligor on the CP cache (convenience for cp -> obligor).
ALTER TABLE cov_credit_proposal_cache ADD obligor_id VARCHAR(64);
CREATE INDEX ix_cp_cache_obligor ON cov_credit_proposal_cache(obligor_id);

-- Entity-key level per source table; existing rows backfill to 'cp_id' (unchanged behavior).
-- The CHECK is declared on the column itself: SQL Server compiles a whole batch before running it, so a
-- separate ALTER referencing a column added earlier in the same script fails with "Invalid column name".
ALTER TABLE cov_data_source_table ADD entity_key_level VARCHAR(16) DEFAULT 'cp_id' NOT NULL
    CONSTRAINT ck_dst_entity_key_level CHECK (entity_key_level IN ('cp_id','obligor_id'));
