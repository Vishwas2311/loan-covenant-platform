-- =====================================================================================
-- Covenant Monitoring — consolidated CREATE-TABLE script (all tables)
-- =====================================================================================
-- This is a single, flattened DDL representing the FINAL schema after applying every
-- Flyway migration (V1 -> V35) in backend/src/main/resources/db/migration.
--
-- It is NOT a Flyway migration itself — it is a convenience/reference script that
-- reflects the end state (columns added/dropped and constraints changed by later
-- migrations have already been folded in). Use Flyway for real deployments.
--
-- Dialect: MSSQL-compatible DDL that also runs on H2 in MODE=MSSQLServer
--          (VARCHAR(36) UUID surrogate keys, DATETIME, BIT, VARBINARY(MAX),
--          named PK/UK/CK/FK constraints).
--
-- Key conventions applied throughout the cov_* tables:
--   * Surrogate id and version columns are VARCHAR(36) (application-assigned UUIDs).
--   * Foreign-key columns that point at a surrogate id are also VARCHAR(36).
--   * Audit timestamp columns are named created_date / modified_date / changed_date.
--   * All date/time columns are DATETIME.
--   * Username columns (created_by / modified_by / changed_by / uploaded_by /
--     triggered_by_user) are VARCHAR(50).
--   * Enumerated (CHECK-constrained) domain values are stored lowercase.
--
-- Table groups:
--   1. Application tables (cov_*)   — 19 tables
--   2. Quartz job-store tables (QRTZ_*) — 11 tables (framework-managed)
--
-- Objects are ordered so foreign-key targets are created before their referrers.
-- =====================================================================================


-- =====================================================================================
-- 1. APPLICATION TABLES (cov_*)
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- cov_data_source : reusable data-source registry (MSSQL or REST)   [V11]
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_data_source (
    id                     VARCHAR(36)   NOT NULL,
    name                   VARCHAR(255)  NOT NULL,
    type                   VARCHAR(16)   NOT NULL,
    description            VARCHAR(2000),
    connection_config_json VARCHAR(2000) NOT NULL,
    active                 BIT           NOT NULL,
    created_date           DATETIME      NOT NULL,
    created_by             VARCHAR(50)   NOT NULL,
    modified_date          DATETIME      NOT NULL,
    modified_by             VARCHAR(50)   NOT NULL,
    version                VARCHAR(36)   NOT NULL,
    CONSTRAINT pk_data_source      PRIMARY KEY (id),
    CONSTRAINT uk_data_source_name UNIQUE (name),
    CONSTRAINT ck_data_source_type CHECK (type IN ('rest','mssql'))
);
CREATE INDEX ix_data_source_active ON cov_data_source(active);


-- -------------------------------------------------------------------------------------
-- cov_data_source_table : tables under a data source (entity-key + as-of column)  [V14]
--   + V26 (US-DS-10): COLUMNAR vs KEY_VALUE layout; KEY_VALUE names a code + value column.
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_data_source_table (
    id                VARCHAR(36)  NOT NULL,
    data_source_id    VARCHAR(36)  NOT NULL,
    name              VARCHAR(255) NOT NULL,
    schema_name       VARCHAR(128),
    table_name        VARCHAR(128) NOT NULL,
    entity_key_column VARCHAR(128) NOT NULL,
    as_of_column      VARCHAR(128) NOT NULL,
    table_layout      VARCHAR(16)  NOT NULL,                  -- V26 (columnar/key_value)
    code_column       VARCHAR(128),                           -- V26 (key_value only)
    value_column      VARCHAR(128),                           -- V26 (key_value only)
    active            BIT          NOT NULL,
    created_date      DATETIME     NOT NULL,
    created_by        VARCHAR(50)  NOT NULL,
    modified_date     DATETIME     NOT NULL,
    modified_by        VARCHAR(50)  NOT NULL,
    version           VARCHAR(36)  NOT NULL,
    CONSTRAINT pk_data_source_table PRIMARY KEY (id),
    CONSTRAINT fk_dst_data_source   FOREIGN KEY (data_source_id) REFERENCES cov_data_source(id),
    CONSTRAINT uk_dst_source_name   UNIQUE (data_source_id, name),
    CONSTRAINT ck_dst_layout        CHECK (table_layout IN ('columnar','key_value'))
);
CREATE INDEX ix_dst_data_source ON cov_data_source_table(data_source_id);


-- -------------------------------------------------------------------------------------
-- cov_measure : reusable measure library referenced by code in covenant formulas  [V14]
--   + V26 (US-DS-10): source_column now nullable; KEY_VALUE measures use source_code.
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_measure (
    id                   VARCHAR(36)  NOT NULL,
    code                 VARCHAR(64)  NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    description          VARCHAR(2000),
    data_source_table_id VARCHAR(36)  NOT NULL,
    source_column        VARCHAR(128),                        -- V26 (now nullable; columnar only)
    source_code          VARCHAR(128),                        -- V26 (key_value only)
    default_aggregation  VARCHAR(16)  NOT NULL,
    data_type            VARCHAR(16)  NOT NULL,
    unit                 VARCHAR(32),
    active               BIT          NOT NULL,
    created_date         DATETIME     NOT NULL,
    created_by           VARCHAR(50)  NOT NULL,
    modified_date        DATETIME     NOT NULL,
    modified_by           VARCHAR(50)  NOT NULL,
    version              VARCHAR(36)  NOT NULL,
    CONSTRAINT pk_measure      PRIMARY KEY (id),
    CONSTRAINT uk_measure_code UNIQUE (code),
    CONSTRAINT fk_measure_dst  FOREIGN KEY (data_source_table_id) REFERENCES cov_data_source_table(id),
    CONSTRAINT ck_measure_agg  CHECK (default_aggregation IN ('avg','sum','min','max','latest','current','count')),
    CONSTRAINT ck_measure_dtype CHECK (data_type IN ('number','string','date','boolean'))
);
CREATE INDEX ix_measure_active ON cov_measure(active);
CREATE INDEX ix_measure_table  ON cov_measure(data_source_table_id);


-- -------------------------------------------------------------------------------------
-- cov_covenant_master : covenant library / master definitions
--   Base V1; evolved by V12, V15 (measure-based DSL), V19 (eligibility + assessment
--   type), V23 (default frequency), V25 (custom).
--   NOTE: the global UNIQUE(name) constraint was dropped in V25 (uniqueness now
--   enforced in the service layer: library = global, custom = per owner_cp_id).
--   V30 dropped the redundant 'type' column; V31 moved condition_type + classification
--   to cov_applied_covenant (they describe how a covenant is applied, not the template).
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_covenant_master (
    id                         VARCHAR(36)   NOT NULL,
    name                       VARCHAR(255)  NOT NULL,
    category                   VARCHAR(32)   NOT NULL,
    description                VARCHAR(2000),
    business_segments          VARCHAR(2000) NOT NULL,
    condition_expression       VARCHAR(2000),                 -- V15 (measure-based DSL)
    referenced_measures        VARCHAR(2000),                 -- V15
    islamic_banking            BIT,                           -- V19
    industries                 VARCHAR(2000),                 -- V19 (JSON code-list)
    countries_of_risk          VARCHAR(2000),                 -- V19
    countries_of_incorporation VARCHAR(2000),                 -- V19
    credit_proposal_types      VARCHAR(2000),                 -- V19
    legal_entity_types         VARCHAR(2000),                 -- V19
    facility_types             VARCHAR(2000),                 -- V19
    collateral_types           VARCHAR(2000),                 -- V19
    assessment_type            VARCHAR(16),                   -- V19 (auto/manual)
    default_frequency          VARCHAR(16)   NOT NULL,        -- V23
    default_cron               VARCHAR(64),                   -- V23 (only when CUSTOM)
    is_custom                  BIT           NOT NULL,        -- V25
    owner_cp_id                VARCHAR(64),                   -- V25 (custom covenant owner CP)
    active                     BIT           NOT NULL,
    created_date               DATETIME      NOT NULL,
    created_by                 VARCHAR(50)   NOT NULL,
    modified_date              DATETIME      NOT NULL,
    modified_by                 VARCHAR(50)   NOT NULL,
    version                    VARCHAR(36)   NOT NULL,
    CONSTRAINT pk_covenant_master            PRIMARY KEY (id),
    CONSTRAINT ck_covenant_master_cat        CHECK (category IN ('financial','non_financial')),
    CONSTRAINT ck_covenant_assessment_type   CHECK (assessment_type IN ('auto','manual'))
);
CREATE INDEX ix_covenant_master_category ON cov_covenant_master(category);
CREATE INDEX ix_covenant_master_active   ON cov_covenant_master(active);
CREATE INDEX ix_covenant_master_owner    ON cov_covenant_master(owner_cp_id);


-- -------------------------------------------------------------------------------------
-- cov_applied_covenant : applied covenants (per CP)   [V3, + condition override in V16]
--   + V31 condition_type + classification (moved from the master); V32 threshold_source
--     provenance (master/user/ml, backfilled to master, NOT NULL).
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_applied_covenant (
    id                   VARCHAR(36)   NOT NULL,
    cp_id                VARCHAR(64)   NOT NULL,
    covenant_master_id   VARCHAR(36)   NOT NULL,
    frequency            VARCHAR(16)   NOT NULL,
    cron_expression      VARCHAR(64),
    start_date           DATE          NOT NULL,
    end_date             DATE,
    status               VARCHAR(16)   NOT NULL,
    snapshot_json        VARCHAR(4000) NOT NULL,
    condition_expression VARCHAR(2000),                       -- V16 (per-CP override)
    condition_type       VARCHAR(16),                         -- V31 (precedent/subsequent)
    classification       VARCHAR(16),                         -- V31 (external/internal)
    threshold_source     VARCHAR(16)   NOT NULL,              -- V32 (master/user/ml)
    created_date         DATETIME      NOT NULL,
    created_by           VARCHAR(50)   NOT NULL,
    modified_date        DATETIME      NOT NULL,
    modified_by           VARCHAR(50)   NOT NULL,
    version              VARCHAR(36)   NOT NULL,
    CONSTRAINT pk_applied_covenant        PRIMARY KEY (id),
    CONSTRAINT fk_applied_covenant_master FOREIGN KEY (covenant_master_id)
        REFERENCES cov_covenant_master(id),
    CONSTRAINT uk_applied_per_cp          UNIQUE (cp_id, covenant_master_id),
    CONSTRAINT ck_applied_status          CHECK (status IN ('active','suspended','expired')),
    CONSTRAINT ck_applied_frequency       CHECK (frequency IN
        ('daily','weekly','monthly','quarterly','yearly','custom')),
    CONSTRAINT ck_applied_condition_type  CHECK (condition_type IN ('precedent','subsequent')),
    CONSTRAINT ck_applied_classification  CHECK (classification IN ('external','internal')),
    CONSTRAINT ck_applied_threshold_source CHECK (threshold_source IN ('master','user','ml'))
);
CREATE INDEX ix_applied_cp     ON cov_applied_covenant(cp_id);
CREATE INDEX ix_applied_status ON cov_applied_covenant(status);


-- -------------------------------------------------------------------------------------
-- cov_monitoring_schedule : Quartz schedule binding per applied covenant   [V4]
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_monitoring_schedule (
    id                  VARCHAR(36)  NOT NULL,
    applied_covenant_id VARCHAR(36)  NOT NULL,
    quartz_job_key      VARCHAR(128) NOT NULL,
    cron_expression     VARCHAR(64)  NOT NULL,
    next_run_at         DATETIME,
    last_run_at         DATETIME,
    status              VARCHAR(16)  NOT NULL,
    created_date        DATETIME     NOT NULL,
    created_by          VARCHAR(50)  NOT NULL,
    modified_date       DATETIME     NOT NULL,
    modified_by          VARCHAR(50)  NOT NULL,
    version             VARCHAR(36)  NOT NULL,
    CONSTRAINT pk_monitoring_schedule PRIMARY KEY (id),
    CONSTRAINT uk_schedule_applied    UNIQUE (applied_covenant_id),
    CONSTRAINT fk_schedule_applied    FOREIGN KEY (applied_covenant_id)
        REFERENCES cov_applied_covenant(id),
    CONSTRAINT ck_schedule_status     CHECK (status IN ('scheduled','paused'))
);
CREATE INDEX ix_schedule_next ON cov_monitoring_schedule(next_run_at);


-- -------------------------------------------------------------------------------------
-- cov_assessment : assessment runs   [V4; + V13 computed value/NA, V19 manual state,
--                  V24 point-in-time condition snapshot]
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_assessment (
    id                   VARCHAR(36)   NOT NULL,
    applied_covenant_id  VARCHAR(36)   NOT NULL,
    scheduled_at         DATETIME      NOT NULL,
    executed_at          DATETIME,
    data_snapshot_json   VARCHAR(4000) NOT NULL,
    evaluated_expression VARCHAR(2000),
    condition_expression VARCHAR(2000),                       -- V24 (raw DSL evaluated)
    computed_value       VARCHAR(2000),                       -- V13
    computed_value_type  VARCHAR(64),                         -- V13
    result               VARCHAR(24)   NOT NULL,
    error_message        VARCHAR(2000),
    assessment_note      VARCHAR(2000),                       -- V19 (manual outcome note)
    workflow_instance_id VARCHAR(128),
    workflow_error       VARCHAR(500),
    triggered_by         VARCHAR(16)   NOT NULL,
    triggered_by_user    VARCHAR(50),
    status               VARCHAR(16)   NOT NULL,
    created_date         DATETIME      NOT NULL,
    created_by           VARCHAR(50)   NOT NULL,
    modified_date        DATETIME      NOT NULL,
    modified_by           VARCHAR(50)   NOT NULL,
    version              VARCHAR(36)   NOT NULL,
    CONSTRAINT pk_assessment         PRIMARY KEY (id),
    CONSTRAINT fk_assessment_applied FOREIGN KEY (applied_covenant_id)
        REFERENCES cov_applied_covenant(id),
    CONSTRAINT ck_assessment_result  CHECK (result IN
        ('compliant','breached','na','error','pending_review')),          -- V13 added na
    CONSTRAINT ck_assessment_status  CHECK (status IN
        ('pending','in_progress','completed','pending_manual')),          -- V19 added pending_manual
    CONSTRAINT ck_assessment_triggered CHECK (triggered_by IN ('scheduled','manual'))
);
CREATE INDEX ix_assessment_applied   ON cov_assessment(applied_covenant_id);
CREATE INDEX ix_assessment_scheduled ON cov_assessment(scheduled_at);
CREATE INDEX ix_assessment_result    ON cov_assessment(result);


-- -------------------------------------------------------------------------------------
-- cov_assessment_threshold : decomposed per-comparison assessment values   [V3, US-ASM-21]
-- -------------------------------------------------------------------------------------
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


-- -------------------------------------------------------------------------------------
-- cov_expression_change_log : audit of covenant master expression changes   [V5]
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_expression_change_log (
    id             VARCHAR(36)   NOT NULL,
    covenant_id    VARCHAR(36)   NOT NULL,
    old_expression VARCHAR(2000),
    new_expression VARCHAR(2000) NOT NULL,
    changed_date   DATETIME      NOT NULL,
    changed_by     VARCHAR(50)   NOT NULL,
    CONSTRAINT pk_expression_change_log PRIMARY KEY (id),
    CONSTRAINT fk_change_log_covenant   FOREIGN KEY (covenant_id)
        REFERENCES cov_covenant_master(id)
);
CREATE INDEX ix_change_log_covenant ON cov_expression_change_log(covenant_id);
CREATE INDEX ix_change_log_at       ON cov_expression_change_log(changed_date);


-- -------------------------------------------------------------------------------------
-- cov_credit_proposal_cache : CP attribute cache (1-hour TTL)   [V7]
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_credit_proposal_cache (
    cp_id            VARCHAR(64)   NOT NULL,
    business_segment VARCHAR(64),
    sector           VARCHAR(64),
    attributes_json  VARCHAR(4000) NOT NULL,
    fetched_at       DATETIME      NOT NULL,
    CONSTRAINT pk_credit_proposal_cache PRIMARY KEY (cp_id)
);
CREATE INDEX ix_cp_cache_fetched_at ON cov_credit_proposal_cache(fetched_at);


-- -------------------------------------------------------------------------------------
-- cov_covenant_recommendation : persisted recommendation history per CP   [V8]
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_covenant_recommendation (
    id                 VARCHAR(36) NOT NULL,
    cp_id              VARCHAR(64) NOT NULL,
    covenant_master_id VARCHAR(36) NOT NULL,
    match_reason       VARCHAR(500),
    status             VARCHAR(16) NOT NULL,
    created_date       DATETIME    NOT NULL,
    modified_date      DATETIME    NOT NULL,
    CONSTRAINT pk_cov_recommendation        PRIMARY KEY (id),
    CONSTRAINT fk_recommendation_master     FOREIGN KEY (covenant_master_id)
        REFERENCES cov_covenant_master(id),
    CONSTRAINT uk_recommendation_cp_master  UNIQUE (cp_id, covenant_master_id),
    CONSTRAINT ck_recommendation_status     CHECK (status IN
        ('suggested','accepted','rejected','manual_add'))
);
CREATE INDEX ix_recommendation_cp     ON cov_covenant_recommendation(cp_id);
CREATE INDEX ix_recommendation_status ON cov_covenant_recommendation(status);


-- -------------------------------------------------------------------------------------
-- cov_applied_covenant_audit : audit trail for frequency/cron/status changes   [V9]
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_applied_covenant_audit (
    id                  VARCHAR(36)  NOT NULL,
    applied_covenant_id VARCHAR(36)  NOT NULL,
    change_type         VARCHAR(32)  NOT NULL,
    old_value           VARCHAR(128),
    new_value           VARCHAR(128),
    changed_date        DATETIME     NOT NULL,
    changed_by          VARCHAR(50)  NOT NULL,
    CONSTRAINT pk_applied_audit      PRIMARY KEY (id),
    CONSTRAINT fk_applied_audit      FOREIGN KEY (applied_covenant_id)
        REFERENCES cov_applied_covenant(id),
    CONSTRAINT ck_applied_audit_type CHECK (change_type IN ('frequency','cron','status'))
);
CREATE INDEX ix_applied_audit_applied ON cov_applied_covenant_audit(applied_covenant_id);
CREATE INDEX ix_applied_audit_at      ON cov_applied_covenant_audit(changed_date);


-- -------------------------------------------------------------------------------------
-- cov_reporting_setting : singleton org reporting-calendar policy for PERIODIC windows
--   [V28] (fiscal-year start month + whether a plain PERIODIC window counts the
--   in-progress period) plus the tunable monitoring-schedule windows [V6]
--   (due-soon / upcoming look-ahead / starting-soon, in days). Seeded with one row.
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_reporting_setting (
    id                               VARCHAR(36)  NOT NULL,
    fiscal_year_start_month          INT          NOT NULL,
    periodic_includes_current_period BIT          NOT NULL,
    due_soon_days                    INT          NOT NULL,
    upcoming_window_days             INT          NOT NULL,
    starting_soon_days               INT          NOT NULL,
    created_date                     DATETIME     NOT NULL,
    created_by                       VARCHAR(50)  NOT NULL,
    modified_date                    DATETIME     NOT NULL,
    modified_by                       VARCHAR(50)  NOT NULL,
    version                          VARCHAR(36)  NOT NULL,
    CONSTRAINT pk_cov_reporting_setting     PRIMARY KEY (id),
    CONSTRAINT ck_reporting_fiscal_month    CHECK (fiscal_year_start_month BETWEEN 1 AND 12),
    CONSTRAINT ck_reporting_due_soon_days   CHECK (due_soon_days BETWEEN 1 AND 365),
    CONSTRAINT ck_reporting_upcoming_days   CHECK (upcoming_window_days BETWEEN 1 AND 365),
    CONSTRAINT ck_reporting_starting_soon_days CHECK (starting_soon_days BETWEEN 1 AND 365)
);


-- -------------------------------------------------------------------------------------
-- cov_threshold_audit : append-only ML / threshold-provenance audit trail   [V32]
--   (US-ML-12/13) — one row per recommended or applied threshold event.
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_threshold_audit (
    id                  VARCHAR(36)   NOT NULL,
    cp_id               VARCHAR(64)   NOT NULL,
    covenant_master_id  VARCHAR(36)   NOT NULL,
    applied_covenant_id VARCHAR(36),
    source              VARCHAR(16)   NOT NULL,
    event               VARCHAR(16)   NOT NULL,
    old_expression      VARCHAR(2000),
    new_expression      VARCHAR(2000),
    confidence          DECIMAL(5,4),
    rationale           VARCHAR(512),
    changed_by          VARCHAR(50)   NOT NULL,
    changed_date        DATETIME      NOT NULL,
    CONSTRAINT pk_threshold_audit        PRIMARY KEY (id),
    CONSTRAINT ck_threshold_audit_source CHECK (source IN ('master','user','ml')),
    CONSTRAINT ck_threshold_audit_event  CHECK (event  IN ('recommended','applied'))
);
CREATE INDEX ix_threshold_audit_cp      ON cov_threshold_audit(cp_id, changed_date);
CREATE INDEX ix_threshold_audit_applied ON cov_threshold_audit(applied_covenant_id);


-- -------------------------------------------------------------------------------------
-- cov_notification_audit : append-only audit of external notification-service calls
--   [V33] (US-MON-14) — one row per call attempt in a daily breach run, keyed by run_id.
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_notification_audit (
    id            VARCHAR(36)  NOT NULL,
    run_id        VARCHAR(64)  NOT NULL,
    email_to      VARCHAR(256),
    email_cc      VARCHAR(256),
    cp_ids        VARCHAR(2000),
    request_body  VARCHAR(4000),
    response_body VARCHAR(4000),
    http_status   INT,
    outcome       VARCHAR(16)  NOT NULL,
    error_message VARCHAR(512),
    duration_ms   BIGINT,
    changed_by    VARCHAR(50)  NOT NULL,
    changed_date  DATETIME     NOT NULL,
    CONSTRAINT pk_notification_audit         PRIMARY KEY (id),
    CONSTRAINT ck_notification_audit_outcome CHECK (outcome IN ('success','failed'))
);
CREATE INDEX ix_notification_audit_run     ON cov_notification_audit(run_id);
CREATE INDEX ix_notification_audit_changed ON cov_notification_audit(changed_date);


-- -------------------------------------------------------------------------------------
-- cov_covenant_disposition : current RM disposition per CP+covenant (updated in place)
--   [V34] (US-MON-16/19). EXTENSION requires an extension_date (enforced by CHECK).
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_covenant_disposition (
    id                  VARCHAR(36)  NOT NULL,
    cp_id               VARCHAR(64)  NOT NULL,
    applied_covenant_id VARCHAR(36)  NOT NULL,
    assessment_id       VARCHAR(36),
    status              VARCHAR(16)  NOT NULL,
    extension_date      DATE,
    status_source       VARCHAR(16)  NOT NULL,
    note                VARCHAR(1000),
    created_date        DATETIME     NOT NULL,
    created_by          VARCHAR(50)  NOT NULL,
    modified_date       DATETIME     NOT NULL,
    modified_by          VARCHAR(50)  NOT NULL,
    version             VARCHAR(36)  NOT NULL,
    CONSTRAINT pk_covenant_disposition        PRIMARY KEY (id),
    CONSTRAINT uk_covenant_disposition        UNIQUE (cp_id, applied_covenant_id),
    CONSTRAINT ck_covenant_disposition_status CHECK (status IN ('complied','breached','waiver','extension')),
    CONSTRAINT ck_covenant_disposition_source CHECK (status_source IN ('system','user','ai')),
    CONSTRAINT ck_covenant_disposition_ext    CHECK (status <> 'extension' OR extension_date IS NOT NULL)
);
CREATE INDEX ix_covenant_disposition_cp ON cov_covenant_disposition(cp_id);


-- -------------------------------------------------------------------------------------
-- cov_covenant_status_audit : append-only trail of every disposition change   [V34]
--   (US-MON-19), mirroring cov_threshold_audit.
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_covenant_status_audit (
    id                  VARCHAR(36)  NOT NULL,
    cp_id               VARCHAR(64)  NOT NULL,
    applied_covenant_id VARCHAR(36)  NOT NULL,
    assessment_id       VARCHAR(36),
    old_status          VARCHAR(16),
    new_status          VARCHAR(16)  NOT NULL,
    old_extension_date  DATE,
    new_extension_date  DATE,
    source              VARCHAR(16)  NOT NULL,
    rationale           VARCHAR(512),
    changed_by          VARCHAR(50)  NOT NULL,
    changed_date        DATETIME     NOT NULL,
    CONSTRAINT pk_covenant_status_audit        PRIMARY KEY (id),
    CONSTRAINT ck_covenant_status_audit_new    CHECK (new_status IN ('complied','breached','waiver','extension')),
    CONSTRAINT ck_covenant_status_audit_source CHECK (source IN ('system','user','ai'))
);
CREATE INDEX ix_covenant_status_audit_cp ON cov_covenant_status_audit(cp_id);
CREATE INDEX ix_covenant_status_audit_ac ON cov_covenant_status_audit(applied_covenant_id);


-- -------------------------------------------------------------------------------------
-- cov_covenant_attachment : evidence files on a CP+covenant row   [V35] (US-MON-17).
--   Stores the opaque fileId from the external file-upload service; created/removed,
--   never mutated (no audit columns).
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_covenant_attachment (
    id                  VARCHAR(36)  NOT NULL,
    cp_id               VARCHAR(64)  NOT NULL,
    applied_covenant_id VARCHAR(36)  NOT NULL,
    file_id             VARCHAR(128) NOT NULL,
    file_name           VARCHAR(260) NOT NULL,
    uploaded_by         VARCHAR(50)  NOT NULL,
    uploaded_at         DATETIME     NOT NULL,
    CONSTRAINT pk_covenant_attachment PRIMARY KEY (id)
);
CREATE INDEX ix_covenant_attachment_ac ON cov_covenant_attachment(applied_covenant_id);
CREATE INDEX ix_covenant_attachment_cp ON cov_covenant_attachment(cp_id);


-- -------------------------------------------------------------------------------------
-- cov_compliance_certificate : CP-level compliance-certificate files   [V35] (US-MON-17).
-- -------------------------------------------------------------------------------------
CREATE TABLE cov_compliance_certificate (
    id          VARCHAR(36)  NOT NULL,
    cp_id       VARCHAR(64)  NOT NULL,
    file_id     VARCHAR(128) NOT NULL,
    file_name   VARCHAR(260) NOT NULL,
    uploaded_by VARCHAR(50)  NOT NULL,
    uploaded_at DATETIME     NOT NULL,
    CONSTRAINT pk_compliance_certificate PRIMARY KEY (id)
);
CREATE INDEX ix_compliance_certificate_cp ON cov_compliance_certificate(cp_id);


-- =====================================================================================
-- 2. QUARTZ JOB-STORE TABLES (QRTZ_*)   [V6; boolean columns fixed to BIT in V17]
--    Framework-managed (org.quartz.jobStore.tablePrefix=QRTZ_) — intentionally not
--    'cov_' prefixed. Vendored from the official Quartz 2.3.x SQL Server schema.
-- =====================================================================================

CREATE TABLE QRTZ_JOB_DETAILS (
    SCHED_NAME        VARCHAR(120)   NOT NULL,
    JOB_NAME          VARCHAR(200)   NOT NULL,
    JOB_GROUP         VARCHAR(200)   NOT NULL,
    DESCRIPTION       VARCHAR(250)   NULL,
    JOB_CLASS_NAME    VARCHAR(250)   NOT NULL,
    IS_DURABLE        BIT            NOT NULL,   -- V17: VARCHAR(1) -> BIT
    IS_NONCONCURRENT  BIT            NOT NULL,   -- V17
    IS_UPDATE_DATA    BIT            NOT NULL,   -- V17
    REQUESTS_RECOVERY BIT            NOT NULL,   -- V17
    JOB_DATA          VARBINARY(MAX) NULL,
    CONSTRAINT PK_QRTZ_JOB_DETAILS PRIMARY KEY (SCHED_NAME, JOB_NAME, JOB_GROUP)
);

CREATE TABLE QRTZ_TRIGGERS (
    SCHED_NAME     VARCHAR(120)   NOT NULL,
    TRIGGER_NAME   VARCHAR(200)   NOT NULL,
    TRIGGER_GROUP  VARCHAR(200)   NOT NULL,
    JOB_NAME       VARCHAR(200)   NOT NULL,
    JOB_GROUP      VARCHAR(200)   NOT NULL,
    DESCRIPTION    VARCHAR(250)   NULL,
    NEXT_FIRE_TIME BIGINT         NULL,
    PREV_FIRE_TIME BIGINT         NULL,
    PRIORITY       INT            NULL,
    TRIGGER_STATE  VARCHAR(16)    NOT NULL,
    TRIGGER_TYPE   VARCHAR(8)     NOT NULL,
    START_TIME     BIGINT         NOT NULL,
    END_TIME       BIGINT         NULL,
    CALENDAR_NAME  VARCHAR(200)   NULL,
    MISFIRE_INSTR  SMALLINT       NULL,
    JOB_DATA       VARBINARY(MAX) NULL,
    CONSTRAINT PK_QRTZ_TRIGGERS PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
    CONSTRAINT FK_QRTZ_TRIGGERS_JOB FOREIGN KEY (SCHED_NAME, JOB_NAME, JOB_GROUP)
        REFERENCES QRTZ_JOB_DETAILS(SCHED_NAME, JOB_NAME, JOB_GROUP)
);

CREATE TABLE QRTZ_SIMPLE_TRIGGERS (
    SCHED_NAME      VARCHAR(120) NOT NULL,
    TRIGGER_NAME    VARCHAR(200) NOT NULL,
    TRIGGER_GROUP   VARCHAR(200) NOT NULL,
    REPEAT_COUNT    BIGINT       NOT NULL,
    REPEAT_INTERVAL BIGINT       NOT NULL,
    TIMES_TRIGGERED BIGINT       NOT NULL,
    CONSTRAINT PK_QRTZ_SIMPLE_TRIGGERS PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
    CONSTRAINT FK_QRTZ_SIMPLE_TRIGGERS FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
        REFERENCES QRTZ_TRIGGERS(SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_CRON_TRIGGERS (
    SCHED_NAME      VARCHAR(120) NOT NULL,
    TRIGGER_NAME    VARCHAR(200) NOT NULL,
    TRIGGER_GROUP   VARCHAR(200) NOT NULL,
    CRON_EXPRESSION VARCHAR(120) NOT NULL,
    TIME_ZONE_ID    VARCHAR(80),
    CONSTRAINT PK_QRTZ_CRON_TRIGGERS PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
    CONSTRAINT FK_QRTZ_CRON_TRIGGERS FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
        REFERENCES QRTZ_TRIGGERS(SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_SIMPROP_TRIGGERS (
    SCHED_NAME    VARCHAR(120)  NOT NULL,
    TRIGGER_NAME  VARCHAR(200)  NOT NULL,
    TRIGGER_GROUP VARCHAR(200)  NOT NULL,
    STR_PROP_1    VARCHAR(512)  NULL,
    STR_PROP_2    VARCHAR(512)  NULL,
    STR_PROP_3    VARCHAR(512)  NULL,
    INT_PROP_1    INT           NULL,
    INT_PROP_2    INT           NULL,
    LONG_PROP_1   BIGINT        NULL,
    LONG_PROP_2   BIGINT        NULL,
    DEC_PROP_1    NUMERIC(13,4) NULL,
    DEC_PROP_2    NUMERIC(13,4) NULL,
    BOOL_PROP_1   BIT           NULL,   -- V17: VARCHAR(1) -> BIT
    BOOL_PROP_2   BIT           NULL,   -- V17
    CONSTRAINT PK_QRTZ_SIMPROP_TRIGGERS PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
    CONSTRAINT FK_QRTZ_SIMPROP_TRIGGERS FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
        REFERENCES QRTZ_TRIGGERS(SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_BLOB_TRIGGERS (
    SCHED_NAME    VARCHAR(120)   NOT NULL,
    TRIGGER_NAME  VARCHAR(200)   NOT NULL,
    TRIGGER_GROUP VARCHAR(200)   NOT NULL,
    BLOB_DATA     VARBINARY(MAX) NULL,
    CONSTRAINT PK_QRTZ_BLOB_TRIGGERS PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
    CONSTRAINT FK_QRTZ_BLOB_TRIGGERS FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
        REFERENCES QRTZ_TRIGGERS(SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_CALENDARS (
    SCHED_NAME    VARCHAR(120)   NOT NULL,
    CALENDAR_NAME VARCHAR(200)   NOT NULL,
    CALENDAR      VARBINARY(MAX) NOT NULL,
    CONSTRAINT PK_QRTZ_CALENDARS PRIMARY KEY (SCHED_NAME, CALENDAR_NAME)
);

CREATE TABLE QRTZ_PAUSED_TRIGGER_GRPS (
    SCHED_NAME    VARCHAR(120) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    CONSTRAINT PK_QRTZ_PAUSED_TRIGGER_GRPS PRIMARY KEY (SCHED_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_FIRED_TRIGGERS (
    SCHED_NAME        VARCHAR(120) NOT NULL,
    ENTRY_ID          VARCHAR(95)  NOT NULL,
    TRIGGER_NAME      VARCHAR(200) NOT NULL,
    TRIGGER_GROUP     VARCHAR(200) NOT NULL,
    INSTANCE_NAME     VARCHAR(200) NOT NULL,
    FIRED_TIME        BIGINT       NOT NULL,
    SCHED_TIME        BIGINT       NOT NULL,
    PRIORITY          INT          NOT NULL,
    STATE             VARCHAR(16)  NOT NULL,
    JOB_NAME          VARCHAR(200) NULL,
    JOB_GROUP         VARCHAR(200) NULL,
    IS_NONCONCURRENT  BIT          NULL,   -- V17: VARCHAR(1) -> BIT
    REQUESTS_RECOVERY BIT          NULL,   -- V17
    CONSTRAINT PK_QRTZ_FIRED_TRIGGERS PRIMARY KEY (SCHED_NAME, ENTRY_ID)
);

CREATE TABLE QRTZ_SCHEDULER_STATE (
    SCHED_NAME        VARCHAR(120) NOT NULL,
    INSTANCE_NAME     VARCHAR(200) NOT NULL,
    LAST_CHECKIN_TIME BIGINT       NOT NULL,
    CHECKIN_INTERVAL  BIGINT       NOT NULL,
    CONSTRAINT PK_QRTZ_SCHEDULER_STATE PRIMARY KEY (SCHED_NAME, INSTANCE_NAME)
);

CREATE TABLE QRTZ_LOCKS (
    SCHED_NAME VARCHAR(120) NOT NULL,
    LOCK_NAME  VARCHAR(40)  NOT NULL,
    CONSTRAINT PK_QRTZ_LOCKS PRIMARY KEY (SCHED_NAME, LOCK_NAME)
);

-- Quartz indexes
CREATE INDEX IDX_QRTZ_J_REQ_RECOVERY    ON QRTZ_JOB_DETAILS(SCHED_NAME, REQUESTS_RECOVERY);
CREATE INDEX IDX_QRTZ_J_GRP             ON QRTZ_JOB_DETAILS(SCHED_NAME, JOB_GROUP);

CREATE INDEX IDX_QRTZ_T_J               ON QRTZ_TRIGGERS(SCHED_NAME, JOB_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_T_JG              ON QRTZ_TRIGGERS(SCHED_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_T_C               ON QRTZ_TRIGGERS(SCHED_NAME, CALENDAR_NAME);
CREATE INDEX IDX_QRTZ_T_G               ON QRTZ_TRIGGERS(SCHED_NAME, TRIGGER_GROUP);
CREATE INDEX IDX_QRTZ_T_STATE           ON QRTZ_TRIGGERS(SCHED_NAME, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_N_STATE         ON QRTZ_TRIGGERS(SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_N_G_STATE       ON QRTZ_TRIGGERS(SCHED_NAME, TRIGGER_GROUP, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_NEXT_FIRE_TIME  ON QRTZ_TRIGGERS(SCHED_NAME, NEXT_FIRE_TIME);
CREATE INDEX IDX_QRTZ_T_NFT_ST          ON QRTZ_TRIGGERS(SCHED_NAME, TRIGGER_STATE, NEXT_FIRE_TIME);
CREATE INDEX IDX_QRTZ_T_NFT_MISFIRE     ON QRTZ_TRIGGERS(SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME);
CREATE INDEX IDX_QRTZ_T_NFT_ST_MISFIRE  ON QRTZ_TRIGGERS(SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_NFT_ST_MIS_GRP  ON QRTZ_TRIGGERS(SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME, TRIGGER_GROUP, TRIGGER_STATE);

CREATE INDEX IDX_QRTZ_FT_TRIG_INST_NAME ON QRTZ_FIRED_TRIGGERS(SCHED_NAME, INSTANCE_NAME);
CREATE INDEX IDX_QRTZ_FT_INST_JOB_RR    ON QRTZ_FIRED_TRIGGERS(SCHED_NAME, INSTANCE_NAME, REQUESTS_RECOVERY);
CREATE INDEX IDX_QRTZ_FT_J_G            ON QRTZ_FIRED_TRIGGERS(SCHED_NAME, JOB_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_FT_JG             ON QRTZ_FIRED_TRIGGERS(SCHED_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_FT_T_G            ON QRTZ_FIRED_TRIGGERS(SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP);
CREATE INDEX IDX_QRTZ_FT_TG             ON QRTZ_FIRED_TRIGGERS(SCHED_NAME, TRIGGER_GROUP);

-- =====================================================================================
-- End of consolidated schema. 30 tables total (19 cov_* + 11 QRTZ_*).
-- =====================================================================================
