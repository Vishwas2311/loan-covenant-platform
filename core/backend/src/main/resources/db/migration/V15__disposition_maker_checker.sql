-- =====================================================================================
-- V15 — Integration R1 (T4, FR-12): maker-checker for Waiver / Extension dispositions.
-- =====================================================================================
-- A disposition recorded by an RM is REQUESTED until an APPROVER (not the requester) approves or
-- rejects it. Only an APPROVED disposition changes the covenant's effective status. The rule is a
-- setting (default ON); with it OFF a disposition is approved on save, as before.
-- Existing rows predate the rule and are treated as APPROVED.
-- =====================================================================================
ALTER TABLE cov_covenant_disposition ADD approval_status VARCHAR(16) DEFAULT 'APPROVED' NOT NULL
    CONSTRAINT ck_covenant_disposition_approval CHECK (approval_status IN ('REQUESTED','APPROVED','REJECTED'));
ALTER TABLE cov_covenant_disposition ADD requested_by VARCHAR(50);
ALTER TABLE cov_covenant_disposition ADD decided_by VARCHAR(50);
ALTER TABLE cov_covenant_disposition ADD decided_at DATETIME;
ALTER TABLE cov_covenant_disposition ADD decision_note VARCHAR(1000);

ALTER TABLE cov_reporting_setting ADD disposition_approval_required BIT DEFAULT 1 NOT NULL;
