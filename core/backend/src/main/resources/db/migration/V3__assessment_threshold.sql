-- =====================================================================================
-- US-ASM-21 : cov_assessment_threshold — decomposed per-comparison assessment values
-- -------------------------------------------------------------------------------------
-- One append-only row per comparison in an assessed condition (e.g. "DSCR > 1.5 AND
-- ICR > 2" produces two rows). Captures the measure (left side), the operator, the
-- threshold literal (right side) and the actual value computed against the CP's data at
-- assessment time, so reports/analytics/audits need not re-parse the expression string.
-- Written once by AssessmentService for AUTO assessments; never updated or deleted.
-- =====================================================================================
CREATE TABLE cov_assessment_threshold (
    id              VARCHAR(36)  NOT NULL,
    assessment_id   VARCHAR(36)  NOT NULL,
    ordinal         INT          NOT NULL,                  -- 0-based position in the expression
    measure         VARCHAR(512) NOT NULL,                  -- left side (FormulaThreshold.label)
    operator        VARCHAR(8)   NOT NULL,                  -- >, >=, ==, <, <=, ...
    threshold_value VARCHAR(512) NOT NULL,                  -- right side (FormulaThreshold.displayValue)
    actual_value    VARCHAR(512),                           -- evaluated left side; null if unresolved
    value_type      VARCHAR(16)  NOT NULL,                  -- number / string / boolean / date
    CONSTRAINT pk_assessment_threshold        PRIMARY KEY (id),
    CONSTRAINT fk_assessment_threshold_assmt  FOREIGN KEY (assessment_id)
        REFERENCES cov_assessment(id),
    CONSTRAINT uk_assessment_threshold_ord    UNIQUE (assessment_id, ordinal),
    CONSTRAINT ck_assessment_threshold_type   CHECK (value_type IN
        ('number','string','boolean','date'))
);
CREATE INDEX ix_assessment_threshold_assmt ON cov_assessment_threshold(assessment_id);
