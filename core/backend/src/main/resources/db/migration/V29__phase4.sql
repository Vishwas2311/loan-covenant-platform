-- =====================================================================================
-- V29 — Phase 4.
--   cov_portal_access      C1: which borrower users may use the portal for which CP (granted by the bank).
--   cov_portal_submission  C1: every certificate or statement a borrower sent through the portal.
--   cov_loan_sync_run      C8: each run of the nightly check against the bank's loan system.
--   cov_loan_sync_change   C8: what that run found changed (owner, status) and acted on.
-- MSSQL-compatible and H2 MODE=MSSQLServer compatible.
-- =====================================================================================

CREATE TABLE cov_portal_access (
    cp_id      VARCHAR(64)  NOT NULL,
    user_id    VARCHAR(64)  NOT NULL,
    granted_by VARCHAR(64)  NOT NULL,
    granted_at DATETIME     NOT NULL,
    CONSTRAINT pk_portal_access PRIMARY KEY (cp_id, user_id)
);
CREATE INDEX ix_portal_access_user ON cov_portal_access(user_id);

CREATE TABLE cov_portal_submission (
    id           VARCHAR(36)   NOT NULL,
    cp_id        VARCHAR(64)   NOT NULL,
    kind         VARCHAR(16)   NOT NULL,
    file_name    VARCHAR(255)  NOT NULL,
    period_end   DATE,
    submitted_by VARCHAR(64)   NOT NULL,
    submitted_at DATETIME      NOT NULL,
    note         VARCHAR(500),
    ref_id       VARCHAR(64),
    CONSTRAINT pk_portal_submission      PRIMARY KEY (id),
    CONSTRAINT ck_portal_submission_kind CHECK (kind IN ('CERTIFICATE','STATEMENT'))
);
CREATE INDEX ix_portal_submission_cp ON cov_portal_submission(cp_id);

CREATE TABLE cov_loan_sync_run (
    id           VARCHAR(36)  NOT NULL,
    started_at   DATETIME     NOT NULL,
    finished_at  DATETIME,
    cps_checked  INT          NOT NULL,
    cps_changed  INT          NOT NULL,
    cps_closed   INT          NOT NULL,
    errors       INT          NOT NULL,
    triggered_by VARCHAR(64)  NOT NULL,
    CONSTRAINT pk_loan_sync_run PRIMARY KEY (id)
);

CREATE TABLE cov_loan_sync_change (
    id        VARCHAR(36)   NOT NULL,
    run_id    VARCHAR(36)   NOT NULL,
    cp_id     VARCHAR(64)   NOT NULL,
    field     VARCHAR(32)   NOT NULL,
    old_value VARCHAR(255),
    new_value VARCHAR(255),
    CONSTRAINT pk_loan_sync_change PRIMARY KEY (id)
);
CREATE INDEX ix_loan_sync_change_run ON cov_loan_sync_change(run_id);
