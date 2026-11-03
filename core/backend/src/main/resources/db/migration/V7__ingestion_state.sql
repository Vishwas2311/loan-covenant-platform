-- =====================================================================================
-- US-DS-13 : cov_ingestion_state — freshness-gate control table
-- -------------------------------------------------------------------------------------
-- One row per (data_source_table, entity_key) recording just enough to make the
-- ingest-or-skip decision cheaply and auditably, without scanning cov_staging_value on
-- every candidate:
--
--   staged_as_of       : the watermark — max as_of_date already staged for the entity
--   staged_ingested_at : when that staged copy was written (compared against source change)
--   source_changed_at  : when the source last published new/revised data (CDC/last_updated
--                        or a force-refresh stamp); NULL when the source cannot report it
--   next_eligible_at   : TTL floor — the gate skips until this instant for sources that
--                        cannot report a change timestamp (per-data-class max-staleness)
--   last_status        : outcome of the most recent decision (ok / skipped / failed)
--
-- The gate ingests iff staged_as_of IS NULL, OR staged_as_of < required_as_of, OR
-- source_changed_at > staged_ingested_at. The UNIQUE (table, entity) key makes the state
-- a single upserted row per entity+source.
-- =====================================================================================
CREATE TABLE cov_ingestion_state (
    id                   VARCHAR(36)    NOT NULL,
    data_source_table_id VARCHAR(36)    NOT NULL,
    entity_key           VARCHAR(128)   NOT NULL,
    staged_as_of         DATE,
    staged_ingested_at   DATETIME,
    source_changed_at    DATETIME,
    next_eligible_at     DATETIME,
    last_status          VARCHAR(16)    NOT NULL,
    created_date         DATETIME       NOT NULL,
    created_by           VARCHAR(50)    NOT NULL,
    modified_date        DATETIME       NOT NULL,
    modified_by          VARCHAR(50)    NOT NULL,
    version              VARCHAR(36)    NOT NULL,
    CONSTRAINT pk_ingestion_state     PRIMARY KEY (id),
    CONSTRAINT fk_ingestion_state_dst FOREIGN KEY (data_source_table_id)
        REFERENCES cov_data_source_table(id),
    CONSTRAINT uk_ingestion_state     UNIQUE (data_source_table_id, entity_key),
    CONSTRAINT ck_ingestion_state_status CHECK (last_status IN ('ok','skipped','failed'))
);
