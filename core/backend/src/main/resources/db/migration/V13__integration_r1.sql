-- =====================================================================================
-- V13 — Integration R1 (Document Processing Service).
--   1. 'extracted' threshold provenance (C1: covenants proposed from the loan agreement).
--   2. cov_data_event: C5 "new data for an entity" events from DPS, idempotent per change.
-- MSSQL-compatible and H2 MODE=MSSQLServer compatible.
-- =====================================================================================

ALTER TABLE cov_applied_covenant DROP CONSTRAINT ck_applied_threshold_source;
ALTER TABLE cov_applied_covenant ADD CONSTRAINT ck_applied_threshold_source
    CHECK (threshold_source IN ('master','user','ml','extracted'));

ALTER TABLE cov_threshold_audit DROP CONSTRAINT ck_threshold_audit_source;
ALTER TABLE cov_threshold_audit ADD CONSTRAINT ck_threshold_audit_source
    CHECK (source IN ('master','user','ml','extracted'));

CREATE TABLE cov_data_event (
    id                  VARCHAR(36)   NOT NULL,
    data_source_id      VARCHAR(36)   NOT NULL,
    entity_key          VARCHAR(128)  NOT NULL,
    entity_key_level    VARCHAR(16)   NOT NULL,
    as_of_date          DATE          NOT NULL,
    changed_at          DATETIME2     NOT NULL,
    document_id         VARCHAR(64),
    received_by         VARCHAR(50)   NOT NULL,
    received_at         DATETIME2     NOT NULL,
    assessments_queued  INT           NOT NULL,
    CONSTRAINT pk_data_event PRIMARY KEY (id),
    CONSTRAINT uk_data_event UNIQUE (data_source_id, entity_key, changed_at),
    CONSTRAINT ck_data_event_level CHECK (entity_key_level IN ('cp_id','obligor_id'))
);
CREATE INDEX ix_data_event_entity ON cov_data_event(entity_key, received_at);
