-- =====================================================================================
-- V16 — Integration R1 (T6, FR-22): compliance certificates per CP and reporting period.
-- =====================================================================================
-- A certificate now records the period end it certifies (nullable for files uploaded before this
-- change and for general certificates). Lists can be filtered to one period.
-- =====================================================================================
ALTER TABLE cov_compliance_certificate ADD period_end DATE;
CREATE INDEX ix_compliance_certificate_period ON cov_compliance_certificate(cp_id, period_end);

-- A requirement says which period a CP must certify and by when; its state is derived:
-- RECEIVED once a certificate for that period is uploaded, OVERDUE after the due date, else PENDING.
CREATE TABLE cov_certificate_requirement (
    id            VARCHAR(36)   NOT NULL,
    cp_id         VARCHAR(64)   NOT NULL,
    period_end    DATE          NOT NULL,
    due_date      DATE          NOT NULL,
    note          VARCHAR(1000),
    created_date  DATETIME      NOT NULL,
    created_by    VARCHAR(50)   NOT NULL,
    modified_date DATETIME      NOT NULL,
    modified_by   VARCHAR(50)   NOT NULL,
    version       VARCHAR(36)   NOT NULL,
    CONSTRAINT pk_certificate_requirement PRIMARY KEY (id),
    CONSTRAINT uk_certificate_requirement UNIQUE (cp_id, period_end),
    CONSTRAINT ck_certificate_requirement_due CHECK (due_date >= period_end)
);
