-- =====================================================================================
-- V26 — Phase 2.
--   1. 'amended' threshold audit event: a CP's covenant rule changed after it was applied.
--   2. 'WITHDRAWN' maker-checker state: the requester took a Waiver/Extension request back.
--   3. cov_reporting_setting.at_risk_headroom_percent: a compliant covenant whose headroom is
--      below this share of its limit is flagged "at risk" (0 switches the warning off).
--   4. cov_covenant_master.policy_reference: the bank's credit policy clause behind a covenant.
-- MSSQL-compatible and H2 MODE=MSSQLServer compatible.
-- =====================================================================================

ALTER TABLE cov_threshold_audit DROP CONSTRAINT ck_threshold_audit_event;
ALTER TABLE cov_threshold_audit ADD CONSTRAINT ck_threshold_audit_event
    CHECK (event IN ('recommended','applied','amended'));

ALTER TABLE cov_covenant_disposition DROP CONSTRAINT ck_covenant_disposition_approval;
ALTER TABLE cov_covenant_disposition ADD CONSTRAINT ck_covenant_disposition_approval
    CHECK (approval_status IN ('REQUESTED','APPROVED','REJECTED','WITHDRAWN'));

ALTER TABLE cov_reporting_setting ADD at_risk_headroom_percent INT DEFAULT 10 NOT NULL;

ALTER TABLE cov_covenant_master ADD policy_reference VARCHAR(500);
