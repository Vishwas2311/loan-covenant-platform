-- =====================================================================================
-- US-DS (onboarding journey) : cov_staging_value — durable copy of source data
-- -------------------------------------------------------------------------------------
-- A generic long/EAV staging store holding one row per (table, entity, as-of, item).
-- Every registered data source (MSSQL or REST) is ingested into this table before an
-- assessment runs, so covenants execute against a persisted copy rather than reading the
-- live source per assessment. A single generic table (rather than one physical table per
-- source) keeps the schema static so Hibernate `ddl-auto: validate` and the migration
-- gates hold even though measures/tables are registered by users at runtime.
--
--   entity_key : CP_ID or obligor id, per cov_data_source_table.entity_key_column
--   item_code  : COLUMNAR -> measure.source_column; KEY_VALUE -> measure.source_code
--   value      : exactly one of num/str/date/bool per row, selected by data_type
--
-- The UNIQUE key (table, entity, as-of, item) is the idempotency anchor: JIT ingestion is
-- delete-then-insert scoped to (table, entity, as-of), so re-running never duplicates.
-- =====================================================================================
CREATE TABLE cov_staging_value (
    id                   VARCHAR(36)    NOT NULL,
    data_source_table_id VARCHAR(36)    NOT NULL,
    entity_key           VARCHAR(128)   NOT NULL,
    as_of_date           DATE           NOT NULL,
    item_code            VARCHAR(128)   NOT NULL,
    num_value            DECIMAL(38,6),
    str_value            VARCHAR(2000),
    date_value           DATE,
    bool_value           BIT,
    data_type            VARCHAR(16)    NOT NULL,
    ingested_at          DATETIME       NOT NULL,
    ingest_batch_id      VARCHAR(36)    NOT NULL,
    created_date         DATETIME       NOT NULL,
    created_by           VARCHAR(50)    NOT NULL,
    modified_date        DATETIME       NOT NULL,
    modified_by          VARCHAR(50)    NOT NULL,
    version              VARCHAR(36)    NOT NULL,
    CONSTRAINT pk_staging_value       PRIMARY KEY (id),
    CONSTRAINT fk_staging_value_dst   FOREIGN KEY (data_source_table_id)
        REFERENCES cov_data_source_table(id),
    CONSTRAINT uk_staging_value       UNIQUE (data_source_table_id, entity_key, as_of_date, item_code),
    CONSTRAINT ck_staging_value_dtype CHECK (data_type IN ('number','string','date','boolean'))
);
CREATE INDEX ix_staging_value_lookup
    ON cov_staging_value(data_source_table_id, entity_key, item_code, as_of_date);
