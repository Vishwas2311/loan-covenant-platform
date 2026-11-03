-- =====================================================================================
-- Production-support toolkit : cov_ops_audit — append-only trail of support actions
-- -------------------------------------------------------------------------------------
-- Every WRITE performed through the /api/v1/ops/** surface (re-ingest, clear stale
-- ingestion-state, re-assess, destructive CP cleanup) records one row here BEFORE the
-- caller's transaction commits, in its own REQUIRES_NEW transaction, so the outcome
-- survives even when a destructive op rolls back. Read-only diagnostics are NOT audited.
--
--   action         : the ops operation invoked (e.g. reingest / clear-ingestion-state /
--                    reassess / cleanup)
--   target_type    : the kind of entity acted on (cp / applied_covenant / ...)
--   target_id      : the identifier of that entity (nullable for surface-wide actions)
--   performed_by   : resolved X-User header, or 'system' when unattributed
--   params_summary : CR/LF-sanitised summary of the request parameters
--   outcome        : success / failure
--   detail         : optional human/audit-readable note (error text, per-table counts)
--   performed_at   : when the action was attempted
--
-- This table IS the audit, so it deliberately does not carry the cov_* AuditableEntity
-- columns (created_by/version/...) — its own performed_by/performed_at are the record.
-- =====================================================================================
CREATE TABLE cov_ops_audit (
    id             VARCHAR(36)   NOT NULL,
    action         VARCHAR(64)   NOT NULL,
    target_type    VARCHAR(32)   NOT NULL,
    target_id      VARCHAR(128),
    performed_by   VARCHAR(50)   NOT NULL,
    params_summary VARCHAR(2000),
    outcome        VARCHAR(16)   NOT NULL,
    detail         VARCHAR(2000),
    performed_at   DATETIME      NOT NULL,
    CONSTRAINT pk_ops_audit PRIMARY KEY (id),
    CONSTRAINT ck_ops_audit_outcome CHECK (outcome IN ('success','failure'))
);

CREATE INDEX ix_ops_audit_target ON cov_ops_audit(target_type, target_id, performed_at);
