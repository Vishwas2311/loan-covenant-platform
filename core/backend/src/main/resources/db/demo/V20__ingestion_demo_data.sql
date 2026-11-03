-- =====================================================================================
-- V20 (DEV only) — ingestion-orchestration demo extension (US-DS-11 / US-DS-12 / US-DS-13).
-- =====================================================================================
-- Loaded only under the `dev` profile (classpath:db/demo), AFTER V10. Purely ADDITIVE — it
-- never edits or deletes V10 rows, so every existing demo covenant/assessment is unaffected
-- (all V10 tables are entity_key_level = 'cp_id', so obligor data below is invisible to them).
--
-- It onboards the data needed to exercise the newer ingestion features by hand in dev:
--   (A) deeper cp_metrics_demo history for CP-1001 → a multi-year windowed aggregation
--       (e.g. AVG(DSCR,'4q',PERIODIC)) resolves on the FIRST assessment (US-DS-12 parity:
--       an MSSQL ingest copies full history <= as-of in one shot).
--   (B) obligor_metrics_demo — an external OBLIGOR-grain source (not a cov_ table, like
--       cp_metrics_demo) + its cov_ registration at entity_key_level = 'obligor_id', so an
--       obligor-keyed covenant fans one obligor's data out to all its CPs (US-DS-11).
--   (C) obligor -> CP mapping (cov_obligor_cp_map) + the denormalized obligor_id on the CP
--       cache. In prod these are captured at CP lookup; seeded here so the fan-out is
--       testable without a live upstream.
--
-- The freshness gate + pre-stage sweep (US-DS-13) need no extra data — their behaviour is
-- observable on ANY assessment (assess twice: the 2nd run finds the copy fresh and SKIPS).
-- =====================================================================================


-- =====================================================================================
-- (A) Deeper history for CP-1001 on the columnar MSSQL source (cp_metrics_demo).
-- -------------------------------------------------------------------------------------
-- Seven earlier quarters (2024-03-31 .. 2025-09-30). With V10's 2025-12-31 and 2026-03-31
-- rows this gives CP-1001 nine consecutive quarters — enough for a 4q / 8q PERIODIC window.
-- PK is (cp_id, as_of_date); none of these dates collide with V10.
-- =====================================================================================
INSERT INTO cp_metrics_demo (cp_id, as_of_date, dscr, net_leverage, interest_cover, tnw_inr_cr, co2_intensity, crude_output_mt, recv_cover, export_licence_valid) VALUES
    ('CP-1001', '2024-03-31', 1.70, 3.10, 4.80, 54000.00, 1.95, 6.80, 1.45, 1),
    ('CP-1001', '2024-06-30', 1.72, 3.05, 4.85, 55000.00, 1.94, 6.85, 1.46, 1),
    ('CP-1001', '2024-09-30', 1.74, 3.02, 4.90, 56000.00, 1.93, 6.90, 1.47, 1),
    ('CP-1001', '2024-12-31', 1.76, 3.00, 4.95, 57000.00, 1.92, 6.95, 1.48, 1),
    ('CP-1001', '2025-03-31', 1.78, 2.99, 5.00, 58000.00, 1.91, 7.00, 1.50, 1),
    ('CP-1001', '2025-06-30', 1.79, 2.98, 5.05, 59000.00, 1.90, 7.05, 1.51, 1),
    ('CP-1001', '2025-09-30', 1.80, 2.96, 5.08, 59800.00, 1.89, 7.08, 1.51, 1);


-- =====================================================================================
-- (B) obligor_metrics_demo — external simulated MSSQL source at OBLIGOR grain.
-- -------------------------------------------------------------------------------------
-- Group-level financials keyed by obligor_id (shared across the obligor's CPs). Like
-- cp_metrics_demo this is deliberately NOT a cov_ table — it simulates an external source.
-- =====================================================================================
CREATE TABLE obligor_metrics_demo (
    obligor_id          VARCHAR(64) NOT NULL,
    as_of_date          DATE        NOT NULL,
    group_dscr          DECIMAL(18,4),
    group_net_leverage  DECIMAL(18,4),
    CONSTRAINT pk_obligor_metrics_demo PRIMARY KEY (obligor_id, as_of_date)
);

-- Five quarters for OBL-RIL (comfortably COMPLIANT for AVG(OBL_DSCR,'4q',PERIODIC) >= 1.50)
-- and two for OBL-TATA (tighter, still compliant on LATEST).
INSERT INTO obligor_metrics_demo (obligor_id, as_of_date, group_dscr, group_net_leverage) VALUES
    ('OBL-RIL',  '2025-03-31', 1.90, 2.70),
    ('OBL-RIL',  '2025-06-30', 1.88, 2.72),
    ('OBL-RIL',  '2025-09-30', 1.86, 2.74),
    ('OBL-RIL',  '2025-12-31', 1.84, 2.76),
    ('OBL-RIL',  '2026-03-31', 1.82, 2.78),
    ('OBL-TATA', '2025-12-31', 1.55, 3.45),
    ('OBL-TATA', '2026-03-31', 1.53, 3.48);


-- =====================================================================================
-- (C) Obligor -> CP mapping (source of truth) + denormalized obligor_id on the CP cache.
-- -------------------------------------------------------------------------------------
-- Groups follow the seeded borrowers: Reliance = CP-1001/1002/1003; Tata Steel = CP-1004/1005.
-- UNIQUE(cp_id) enforces "many CPs -> one obligor". Captured at CP lookup in prod.
-- =====================================================================================
INSERT INTO cov_obligor_cp_map (id, obligor_id, cp_id, created_date, created_by, modified_date, modified_by, version) VALUES
    ('0b100000-0000-0000-0000-000000000001', 'OBL-RIL',  'CP-1001', '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0'),
    ('0b100000-0000-0000-0000-000000000002', 'OBL-RIL',  'CP-1002', '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0'),
    ('0b100000-0000-0000-0000-000000000003', 'OBL-RIL',  'CP-1003', '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0'),
    ('0b100000-0000-0000-0000-000000000004', 'OBL-TATA', 'CP-1004', '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0'),
    ('0b100000-0000-0000-0000-000000000005', 'OBL-TATA', 'CP-1005', '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0');

UPDATE cov_credit_proposal_cache SET obligor_id = 'OBL-RIL'  WHERE cp_id IN ('CP-1001', 'CP-1002', 'CP-1003');
UPDATE cov_credit_proposal_cache SET obligor_id = 'OBL-TATA' WHERE cp_id IN ('CP-1004', 'CP-1005');


-- =====================================================================================
-- (D) Register the obligor-grained source/table/measure as cov_ metadata.
-- -------------------------------------------------------------------------------------
-- This is the "onboarding" the UI/REST would otherwise create. Distinct id prefixes
-- (…0100) so there is no collision with V10 (…0001..0008).
-- =====================================================================================
INSERT INTO cov_data_source
    (id, name, type, description, connection_config_json, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('d5000000-0000-0000-0000-000000000100', 'Demo Group Financials (Obligor)', 'mssql',
        'Group/obligor-grain financials shared across an obligor''s CPs; in dev this simulated external source points at the app H2 file DB (AUTO_SERVER) so obligor_metrics_demo resolves end-to-end.',
        '{"jdbcUrl":"jdbc:h2:file:./data/covenant;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE;AUTO_SERVER=TRUE","username":"sa","password":"","tableName":"obligor_metrics_demo"}',
        1, '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0');

-- Obligor-keyed table: entity_key_column is the obligor id column; entity_key_level = 'obligor_id'.
INSERT INTO cov_data_source_table
    (id, data_source_id, name, schema_name, table_name, entity_key_column, as_of_column,
     table_layout, code_column, value_column, active,
     created_date, created_by, modified_date, modified_by, version, entity_key_level) VALUES
    ('d7000000-0000-0000-0000-000000000100', 'd5000000-0000-0000-0000-000000000100',
        'Group Financials (Obligor)', NULL, 'obligor_metrics_demo', 'obligor_id', 'as_of_date',
        'columnar', NULL, NULL, 1,
        '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0', 'obligor_id');

-- Obligor-level DSCR measure (columnar → source_column = physical column).
INSERT INTO cov_measure
    (id, code, name, description, data_source_table_id, source_column, source_code,
     default_aggregation, data_type, unit, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('3e000000-0000-0000-0000-000000000100', 'OBL_DSCR', 'Group DSCR (Obligor)',
        'Obligor-level debt service coverage ratio, shared across the obligor''s CPs.',
        'd7000000-0000-0000-0000-000000000100', 'group_dscr', NULL,
        'latest', 'number', 'x', 1,
        '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0');


-- =====================================================================================
-- End of V20 ingestion-orchestration demo extension.
-- =====================================================================================
