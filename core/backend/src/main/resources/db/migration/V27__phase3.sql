-- =====================================================================================
-- V27 — Phase 3.
--   cov_work_task          C4: the work queue. One open task per (kind, covenant); assignee, due
--                          date, note, done/resolved. Escalated = open past its due date.
--   cov_borrower_contact   C2: who at the borrower receives reminders for a CP.
--   cov_reminder_log       C2: every reminder sent (one per requirement, kind and day at most).
--   cov_saved_report       C11: a user's saved report (columns + filters) and its e-mail schedule.
--   cov_letter_log         C12: every borrower letter generated.
-- MSSQL-compatible and H2 MODE=MSSQLServer compatible.
-- =====================================================================================

CREATE TABLE cov_work_task (
    id                  VARCHAR(36)   NOT NULL,
    cp_id               VARCHAR(64)   NOT NULL,
    applied_covenant_id VARCHAR(36),
    kind                VARCHAR(16)   NOT NULL,
    title               VARCHAR(500)  NOT NULL,
    assignee            VARCHAR(64)   NOT NULL,
    due_date            DATE          NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    note                VARCHAR(1000),
    created_at          DATETIME      NOT NULL,
    closed_at           DATETIME,
    closed_by           VARCHAR(64),
    CONSTRAINT pk_work_task        PRIMARY KEY (id),
    CONSTRAINT ck_work_task_kind   CHECK (kind IN ('APPROVAL','BREACH','OVERDUE','REVIEW','CERTIFICATE')),
    CONSTRAINT ck_work_task_status CHECK (status IN ('OPEN','DONE','RESOLVED'))
);
CREATE INDEX ix_work_task_cp_status ON cov_work_task(cp_id, status);
CREATE INDEX ix_work_task_assignee  ON cov_work_task(assignee, status);

CREATE TABLE cov_borrower_contact (
    cp_id        VARCHAR(64)   NOT NULL,
    contact_name VARCHAR(200)  NOT NULL,
    email        VARCHAR(254)  NOT NULL,
    reminders_on BIT           DEFAULT 1 NOT NULL,
    days_before  INT           DEFAULT 7 NOT NULL,
    updated_by   VARCHAR(64)   NOT NULL,
    updated_at   DATETIME      NOT NULL,
    CONSTRAINT pk_borrower_contact PRIMARY KEY (cp_id)
);

CREATE TABLE cov_reminder_log (
    id             VARCHAR(36)   NOT NULL,
    cp_id          VARCHAR(64)   NOT NULL,
    requirement_id VARCHAR(36)   NOT NULL,
    kind           VARCHAR(16)   NOT NULL,
    sent_on        DATE          NOT NULL,
    sent_to        VARCHAR(254)  NOT NULL,
    outcome        VARCHAR(16)   NOT NULL,
    detail         VARCHAR(1000),
    created_at     DATETIME      NOT NULL,
    CONSTRAINT pk_reminder_log      PRIMARY KEY (id),
    CONSTRAINT ck_reminder_log_kind CHECK (kind IN ('DUE_SOON','OVERDUE')),
    CONSTRAINT uk_reminder_once     UNIQUE (requirement_id, kind, sent_on)
);
CREATE INDEX ix_reminder_log_cp ON cov_reminder_log(cp_id);

CREATE TABLE cov_saved_report (
    id              VARCHAR(36)   NOT NULL,
    owner           VARCHAR(64)   NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    definition_json VARCHAR(4000) NOT NULL,
    schedule        VARCHAR(16)   NOT NULL,
    email_to        VARCHAR(500),
    last_sent_on    DATE,
    created_at      DATETIME      NOT NULL,
    CONSTRAINT pk_saved_report          PRIMARY KEY (id),
    CONSTRAINT ck_saved_report_schedule CHECK (schedule IN ('NONE','DAILY','WEEKLY','MONTHLY'))
);
CREATE INDEX ix_saved_report_owner ON cov_saved_report(owner);

CREATE TABLE cov_letter_log (
    id                  VARCHAR(36)   NOT NULL,
    cp_id               VARCHAR(64)   NOT NULL,
    applied_covenant_id VARCHAR(36),
    kind                VARCHAR(24)   NOT NULL,
    created_by          VARCHAR(64)   NOT NULL,
    created_at          DATETIME      NOT NULL,
    CONSTRAINT pk_letter_log PRIMARY KEY (id)
);
CREATE INDEX ix_letter_log_cp ON cov_letter_log(cp_id);
