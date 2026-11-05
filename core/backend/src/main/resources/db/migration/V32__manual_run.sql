-- =====================================================================================
-- V32 - manual daily runs started from the Support screen.
--   The run used to execute inside the HTTP request; with a large book it outlived the gateway's
--   timeout and the user saw 504 for a run that succeeded (review BUG-10). The request now starts the
--   run in the background and returns its id; this row lets any Core instance report its progress.
-- MSSQL-compatible and H2 MODE=MSSQLServer compatible.
-- =====================================================================================

CREATE TABLE cov_manual_run (
    id                   VARCHAR(36)   NOT NULL,
    status               VARCHAR(16)   NOT NULL,
    run_date             DATE          NOT NULL,
    started_by           VARCHAR(255)  NOT NULL,
    started_at           DATETIME2     NOT NULL,
    finished_at          DATETIME2,
    covenants_assessed   INT,
    cps_touched          INT,
    notifications_sent   INT,
    notifications_failed INT,
    error                VARCHAR(1000),
    CONSTRAINT pk_manual_run PRIMARY KEY (id),
    CONSTRAINT ck_manual_run_status CHECK (status IN ('RUNNING', 'DONE', 'FAILED'))
);

CREATE INDEX ix_manual_run_status ON cov_manual_run(status, started_at);
