-- =====================================================================================
-- Covenant Monitoring — consolidated DATA script (companion to all_tables.sql)
-- =====================================================================================
-- Inserts a coherent, FK-consistent dataset for the FINAL schema (after V1 -> V35).
-- It mirrors the 15 sample compliance certificates in
-- docs/sample-compliance-certificates (CP-1001 .. CP-1015): same borrowers, facility
-- purposes, covenant thresholds and the seeded breaches on CP-1002 (Interest Coverage),
-- CP-1005 (Net Leverage), CP-1007 (DSCR) and CP-1013 (CO2 intensity SLT).
--
-- This is NOT a Flyway migration — it is a reference/convenience script, the data
-- counterpart to all_tables.sql. Run it against an empty schema created by all_tables.sql.
--
-- Conventions (match all_tables.sql):
--   * Surrogate id and version columns are application-assigned VARCHAR(36) UUIDs, so
--     there is NO SET IDENTITY_INSERT — every row supplies its own id explicitly.
--   * Foreign keys reference those UUIDs directly.
--   * Audit timestamp columns are created_date / modified_date / changed_date.
--   * Enumerated (CHECK-constrained) domain values are lowercase.
--
-- Dialect: MSSQL, and also runs on H2 in MODE=MSSQLServer.
--
-- Load order respects foreign keys:
--   reference_data, reporting_setting  (independent catalogs / singleton)
--   data_source -> data_source_table -> measure
--   covenant_master
--   credit_proposal_cache
--   applied_covenant -> monitoring_schedule, assessment
--   covenant_recommendation, applied_covenant_audit, expression_change_log
--   threshold_audit, notification_audit
--   covenant_disposition, covenant_status_audit
--   covenant_attachment, compliance_certificate
--
-- The Quartz QRTZ_* tables are runtime-managed by the scheduler and are intentionally
-- NOT seeded here (seeding them by hand corrupts the job store).
-- =====================================================================================


-- =====================================================================================
-- 1. cov_reporting_setting — singleton org reporting-calendar policy (from V28)
-- =====================================================================================
INSERT INTO cov_reporting_setting
    (id, fiscal_year_start_month, periodic_includes_current_period,
     due_soon_days, upcoming_window_days, starting_soon_days,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('4e000000-0000-0000-0000-000000000001', 4, 0,
        7, 30, 14,
        '2025-01-01 00:00:00', 'seed', '2025-01-01 00:00:00', 'seed', '0');


-- =====================================================================================
-- 2. cov_data_source — one MSSQL financials source + one REST source
-- =====================================================================================
INSERT INTO cov_data_source
    (id, name, type, description, connection_config_json, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('d5000000-0000-0000-0000-000000000001', 'Demo MSSQL Financials', 'mssql',
        'Quarterly borrower financial metrics (columnar).',
        '{"url":"jdbc:sqlserver://demo-financials;databaseName=fin","table":"cp_metrics_demo"}',
        1, '2025-01-05 09:00:00', 'seed', '2025-01-05 09:00:00', 'seed', '0'),
    ('d5000000-0000-0000-0000-000000000002', 'CP Attributes REST API', 'rest',
        'Credit-proposal attribute service (eligibility + segment).',
        '{"baseUrl":"http://localhost:9311/api/v1/credit-proposals"}',
        1, '2025-01-05 09:00:00', 'seed', '2025-01-05 09:00:00', 'seed', '0');


-- =====================================================================================
-- 3. cov_data_source_table — the columnar metrics table under the MSSQL source
-- =====================================================================================
INSERT INTO cov_data_source_table
    (id, data_source_id, name, schema_name, table_name, entity_key_column, as_of_column,
     table_layout, code_column, value_column, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('d7000000-0000-0000-0000-000000000001', 'd5000000-0000-0000-0000-000000000001',
        'CP Quarterly Metrics', 'dbo', 'cp_metrics_demo', 'cp_id', 'as_of_date',
        'columnar', NULL, NULL, 1,
        '2025-01-05 09:10:00', 'seed', '2025-01-05 09:10:00', 'seed', '0');


-- =====================================================================================
-- 4. cov_measure — reusable measures referenced by covenant formulas (code-keyed)
-- =====================================================================================
INSERT INTO cov_measure
    (id, code, name, description, data_source_table_id, source_column, source_code,
     default_aggregation, data_type, unit, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('3e000000-0000-0000-0000-000000000001', 'DSCR',                 'Debt Service Coverage Ratio', 'Cash available for debt service / debt service.',
        'd7000000-0000-0000-0000-000000000001', 'dscr', NULL, 'latest', 'number', 'x', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    ('3e000000-0000-0000-0000-000000000002', 'NET_LEVERAGE',         'Net Debt / EBITDA',           'Net debt divided by EBITDA.',
        'd7000000-0000-0000-0000-000000000001', 'net_leverage', NULL, 'latest', 'number', 'x', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    ('3e000000-0000-0000-0000-000000000003', 'INTEREST_COVER',       'EBITDA / Interest',           'EBITDA divided by interest expense.',
        'd7000000-0000-0000-0000-000000000001', 'interest_cover', NULL, 'latest', 'number', 'x', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    ('3e000000-0000-0000-0000-000000000004', 'TANGIBLE_NET_WORTH',   'Tangible Net Worth',          'Tangible net worth in INR crore.',
        'd7000000-0000-0000-0000-000000000001', 'tnw_inr_cr', NULL, 'latest', 'number', 'INR cr', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    ('3e000000-0000-0000-0000-000000000005', 'CO2_INTENSITY',        'CO2 Intensity',               'Tonnes CO2 per tonne crude steel (SLT).',
        'd7000000-0000-0000-0000-000000000001', 'co2_intensity', NULL, 'latest', 'number', 't/t', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    ('3e000000-0000-0000-0000-000000000006', 'CRUDE_OUTPUT',         'Crude Steel Output',          'Crude steel output in million tonnes.',
        'd7000000-0000-0000-0000-000000000001', 'crude_output_mt', NULL, 'latest', 'number', 'mn t', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    ('3e000000-0000-0000-0000-000000000007', 'RECV_COVER',           'Export Receivables Cover',    'Export receivables cover ratio.',
        'd7000000-0000-0000-0000-000000000001', 'recv_cover', NULL, 'latest', 'number', 'x', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    ('3e000000-0000-0000-0000-000000000008', 'EXPORT_LICENCE_VALID', 'Export Authorisation Valid',  'Defence export authorisation currently valid.',
        'd7000000-0000-0000-0000-000000000001', 'export_licence_valid', NULL, 'current', 'boolean', NULL, 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0');


-- =====================================================================================
-- 5. cov_covenant_master — library covenant definitions used across the CPs
-- =====================================================================================
INSERT INTO cov_covenant_master
    (id, name, category, description, business_segments, condition_expression, referenced_measures,
     islamic_banking, industries, countries_of_risk, countries_of_incorporation,
     credit_proposal_types, legal_entity_types, facility_types, collateral_types,
     assessment_type, default_frequency, default_cron, is_custom, owner_cp_id, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'Minimum DSCR', 'financial',
        'Debt Service Coverage Ratio must stay at or above the agreed floor, tested quarterly.',
        '["CORPORATE","CONGLOMERATE","INFRASTRUCTURE"]', 'LATEST(DSCR) >= 1.50', '["DSCR"]',
        0, NULL, '["IN"]', '["IN"]', '["TERM_LOAN"]', '["PUBLIC_LTD"]', '["TERM_LOAN"]', NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000002', 'Maximum Net Leverage', 'financial',
        'Net debt to EBITDA must not exceed the agreed ceiling.',
        '["CORPORATE","CONGLOMERATE"]', 'LATEST(NET_LEVERAGE) <= 3.50', '["NET_LEVERAGE"]',
        0, NULL, '["IN"]', '["IN"]', '["TERM_LOAN"]', '["PUBLIC_LTD"]', '["TERM_LOAN"]', NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000003', 'Minimum Interest Coverage', 'financial',
        'EBITDA must cover interest expense by at least the agreed multiple.',
        '["CORPORATE","CONGLOMERATE"]', 'LATEST(INTEREST_COVER) >= 4.00', '["INTEREST_COVER"]',
        0, NULL, '["IN"]', '["IN"]', '["TERM_LOAN"]', '["PUBLIC_LTD"]', '["TERM_LOAN"]', NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000004', 'Minimum Tangible Net Worth', 'financial',
        'Tangible net worth must remain at or above the agreed floor (INR crore).',
        '["CORPORATE","CONGLOMERATE"]', 'LATEST(TANGIBLE_NET_WORTH) >= 45000', '["TANGIBLE_NET_WORTH"]',
        0, NULL, '["IN"]', '["IN"]', '["TERM_LOAN"]', '["PUBLIC_LTD"]', '["TERM_LOAN"]', NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000005', 'CO2 Intensity Cap (SLT)', 'financial',
        'Sustainability-linked target: CO2 intensity must not exceed the agreed cap (t/t crude steel).',
        '["CORPORATE"]', 'LATEST(CO2_INTENSITY) <= 2.10', '["CO2_INTENSITY"]',
        0, '["METALS_MINING"]', '["IN"]', '["IN"]', '["TERM_LOAN"]', '["PUBLIC_LTD"]', '["TERM_LOAN"]', NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000006', 'Minimum Crude Steel Output', 'financial',
        'Crude steel output must remain at or above the agreed level (million tonnes).',
        '["CORPORATE"]', 'LATEST(CRUDE_OUTPUT) >= 6.0', '["CRUDE_OUTPUT"]',
        0, '["METALS_MINING"]', '["IN"]', '["IN"]', '["TERM_LOAN"]', '["PUBLIC_LTD"]', '["TERM_LOAN"]', NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000007', 'Export Receivables Cover', 'financial',
        'Export receivables cover ratio must remain at or above the agreed floor.',
        '["CORPORATE","INFRASTRUCTURE"]', 'LATEST(RECV_COVER) >= 1.25', '["RECV_COVER"]',
        0, NULL, '["IN"]', '["IN"]', '["TRADE_FINANCE"]', '["PUBLIC_LTD"]', '["LETTER_OF_CREDIT"]', '["RECEIVABLES"]',
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000008', 'Export Authorisation Valid', 'non_financial',
        'A valid defence-manufacturing / export authorisation must be maintained.',
        '["CORPORATE"]', 'LATEST(EXPORT_LICENCE_VALID) == true', '["EXPORT_LICENCE_VALID"]',
        0, '["MANUFACTURING"]', '["IN"]', '["IN"]', '["TERM_LOAN"]', '["PUBLIC_LTD"]', '["BANK_GUARANTEE"]', NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0');


-- =====================================================================================
-- 6. cov_credit_proposal_cache — one row per CP (matches the sample certificates)
-- =====================================================================================
INSERT INTO cov_credit_proposal_cache (cp_id, business_segment, sector, attributes_json, fetched_at) VALUES
    ('CP-1001', 'CONGLOMERATE', 'OIL_GAS',        '{"borrower":"Reliance Industries Ltd","facility":"Jamnagar Refinery Expansion"}', '2026-04-09 06:00:00'),
    ('CP-1002', 'CONGLOMERATE', 'OIL_GAS',        '{"borrower":"Reliance Industries Ltd","facility":"Retail Working Capital"}',       '2026-04-09 06:00:00'),
    ('CP-1003', 'CONGLOMERATE', 'TELECOM',        '{"borrower":"Reliance Industries Ltd","facility":"Jio Fibre Rollout"}',            '2026-04-09 06:00:00'),
    ('CP-1004', 'CORPORATE',    'METALS_MINING',  '{"borrower":"Tata Steel Ltd","facility":"Kalinganagar Phase II"}',                 '2026-04-09 06:00:00'),
    ('CP-1005', 'CORPORATE',    'METALS_MINING',  '{"borrower":"Tata Steel Ltd","facility":"Raw Material Security"}',                 '2026-04-09 06:00:00'),
    ('CP-1006', 'INFRASTRUCTURE','INFRASTRUCTURE','{"borrower":"Adani Ports and SEZ Ltd","facility":"Mundra Port Expansion"}',        '2026-04-09 06:00:00'),
    ('CP-1007', 'INFRASTRUCTURE','INFRASTRUCTURE','{"borrower":"Adani Ports and SEZ Ltd","facility":"Vizhinjam Terminal"}',           '2026-04-09 06:00:00'),
    ('CP-1008', 'CORPORATE',    'TELECOM',        '{"borrower":"Bharti Airtel Ltd","facility":"5G Spectrum Financing"}',              '2026-04-09 06:00:00'),
    ('CP-1009', 'CORPORATE',    'TELECOM',        '{"borrower":"Bharti Airtel Ltd","facility":"Data Centre Buildout"}',               '2026-04-09 06:00:00'),
    ('CP-1010', 'CORPORATE',    'INFRASTRUCTURE', '{"borrower":"Larsen & Toubro Ltd","facility":"Hyderabad Metro"}',                  '2026-04-09 06:00:00'),
    ('CP-1011', 'CORPORATE',    'MANUFACTURING',  '{"borrower":"Larsen & Toubro Ltd","facility":"Defence Systems Manufacturing"}',    '2026-04-09 06:00:00'),
    ('CP-1012', 'CORPORATE',    'METALS_MINING',  '{"borrower":"JSW Steel Ltd","facility":"Dolvi Works Expansion"}',                  '2026-04-09 06:00:00'),
    ('CP-1013', 'CORPORATE',    'METALS_MINING',  '{"borrower":"JSW Steel Ltd","facility":"Green Steel Transition"}',                 '2026-04-09 06:00:00'),
    ('CP-1014', 'CONGLOMERATE', 'AUTOMOTIVE',     '{"borrower":"Mahindra & Mahindra Ltd","facility":"EV Manufacturing Plant Pune"}',  '2026-04-09 06:00:00'),
    ('CP-1015', 'CONGLOMERATE', 'AUTOMOTIVE',     '{"borrower":"Mahindra & Mahindra Ltd","facility":"Farm Equipment Export Finance"}','2026-04-09 06:00:00');


-- =====================================================================================
-- 7. cov_applied_covenant — 4 covenants per CP
--    threshold_source: mostly master; a few user/ml to exercise provenance.
-- =====================================================================================
INSERT INTO cov_applied_covenant
    (id, cp_id, covenant_master_id, frequency, cron_expression, start_date, end_date, status,
     snapshot_json, condition_expression, condition_type, classification, threshold_source,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('ac000000-0000-0000-0000-000000000001', 'CP-1001', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000002', 'CP-1001', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 3.50',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000003', 'CP-1001', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000004', 'CP-1001', 'c0000000-0000-0000-0000-000000000004', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(TANGIBLE_NET_WORTH) >= 45000','subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000005', 'CP-1002', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000006', 'CP-1002', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 3.50',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000007', 'CP-1002', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 4.00',   'subsequent','external','user',  '2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000008', 'CP-1002', 'c0000000-0000-0000-0000-000000000004', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(TANGIBLE_NET_WORTH) >= 45000','subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000009', 'CP-1003', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000010', 'CP-1003', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 3.50',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000011', 'CP-1003', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000012', 'CP-1003', 'c0000000-0000-0000-0000-000000000007', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(RECV_COVER) >= 1.25',       'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000013', 'CP-1004', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.30',             'subsequent','external','user',  '2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000014', 'CP-1004', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 3.75',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000015', 'CP-1004', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 3.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000016', 'CP-1004', 'c0000000-0000-0000-0000-000000000006', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(CRUDE_OUTPUT) >= 6.0',      'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000017', 'CP-1005', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.30',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000018', 'CP-1005', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 3.75',     'subsequent','external','ml',    '2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000019', 'CP-1005', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 3.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000020', 'CP-1005', 'c0000000-0000-0000-0000-000000000006', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(CRUDE_OUTPUT) >= 6.0',      'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000021', 'CP-1006', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.40',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000022', 'CP-1006', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 4.00',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000023', 'CP-1006', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 3.50',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000024', 'CP-1006', 'c0000000-0000-0000-0000-000000000007', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(RECV_COVER) >= 1.25',       'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000025', 'CP-1007', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.40',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000026', 'CP-1007', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 4.00',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000027', 'CP-1007', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 3.50',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000028', 'CP-1007', 'c0000000-0000-0000-0000-000000000007', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(RECV_COVER) >= 1.25',       'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000029', 'CP-1008', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000030', 'CP-1008', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 3.00',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000031', 'CP-1008', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000032', 'CP-1008', 'c0000000-0000-0000-0000-000000000004', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(TANGIBLE_NET_WORTH) >= 45000','subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000033', 'CP-1009', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000034', 'CP-1009', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 3.00',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000035', 'CP-1009', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000036', 'CP-1009', 'c0000000-0000-0000-0000-000000000004', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(TANGIBLE_NET_WORTH) >= 45000','subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000037', 'CP-1010', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000038', 'CP-1010', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 2.50',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000039', 'CP-1010', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000040', 'CP-1010', 'c0000000-0000-0000-0000-000000000008', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(EXPORT_LICENCE_VALID) == true','precedent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000041', 'CP-1011', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000042', 'CP-1011', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 2.50',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000043', 'CP-1011', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000044', 'CP-1011', 'c0000000-0000-0000-0000-000000000008', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(EXPORT_LICENCE_VALID) == true','precedent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000045', 'CP-1012', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 3.75',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000046', 'CP-1012', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.30',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000047', 'CP-1012', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 3.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000048', 'CP-1012', 'c0000000-0000-0000-0000-000000000006', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(CRUDE_OUTPUT) >= 6.0',      'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000049', 'CP-1013', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 3.75',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000050', 'CP-1013', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.30',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000051', 'CP-1013', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 3.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000052', 'CP-1013', 'c0000000-0000-0000-0000-000000000005', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(CO2_INTENSITY) <= 2.10',    'subsequent','external','ml',    '2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000053', 'CP-1014', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000054', 'CP-1014', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 2.00',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000055', 'CP-1014', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 5.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000056', 'CP-1014', 'c0000000-0000-0000-0000-000000000004', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(TANGIBLE_NET_WORTH) >= 45000','subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000057', 'CP-1015', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(DSCR) >= 1.40',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000058', 'CP-1015', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(NET_LEVERAGE) <= 2.50',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000059', 'CP-1015', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(INTEREST_COVER) >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000060', 'CP-1015', 'c0000000-0000-0000-0000-000000000007', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'LATEST(RECV_COVER) >= 1.25',       'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0');


-- =====================================================================================
-- 8. cov_assessment — latest quarter run per applied covenant
--    Breaches on applied ids 07 (CP-1002 Interest Cover), 18 (CP-1005 Net Leverage),
--    25 (CP-1007 DSCR) and 52 (CP-1013 CO2 intensity SLT).
-- =====================================================================================
INSERT INTO cov_assessment
    (id, applied_covenant_id, scheduled_at, executed_at, data_snapshot_json,
     evaluated_expression, condition_expression, computed_value, computed_value_type,
     result, error_message, assessment_note, workflow_instance_id, workflow_error,
     triggered_by, triggered_by_user, status,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('a5000000-0000-0000-0000-000000000001', 'ac000000-0000-0000-0000-000000000001', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.50',             'LATEST(DSCR) >= 1.50',             '1.85',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000002', 'ac000000-0000-0000-0000-000000000002', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 3.50',     'LATEST(NET_LEVERAGE) <= 3.50',     '2.90',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000003', 'ac000000-0000-0000-0000-000000000003', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 4.00',   'LATEST(INTEREST_COVER) >= 4.00',   '5.20',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000004', 'ac000000-0000-0000-0000-000000000004', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(TANGIBLE_NET_WORTH) >= 45000','LATEST(TANGIBLE_NET_WORTH) >= 45000','62000','number','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000005', 'ac000000-0000-0000-0000-000000000005', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.50',             'LATEST(DSCR) >= 1.50',             '1.72',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000006', 'ac000000-0000-0000-0000-000000000006', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 3.50',     'LATEST(NET_LEVERAGE) <= 3.50',     '3.10',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000007', 'ac000000-0000-0000-0000-000000000007', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 4.00',   'LATEST(INTEREST_COVER) >= 4.00',   '3.10',  'number',  'breached',  NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000008', 'ac000000-0000-0000-0000-000000000008', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(TANGIBLE_NET_WORTH) >= 45000','LATEST(TANGIBLE_NET_WORTH) >= 45000','58000','number','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000009', 'ac000000-0000-0000-0000-000000000009', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.50',             'LATEST(DSCR) >= 1.50',             '1.95',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000010', 'ac000000-0000-0000-0000-000000000010', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 3.50',     'LATEST(NET_LEVERAGE) <= 3.50',     '2.75',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000011', 'ac000000-0000-0000-0000-000000000011', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 4.00',   'LATEST(INTEREST_COVER) >= 4.00',   '6.10',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000012', 'ac000000-0000-0000-0000-000000000012', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(RECV_COVER) >= 1.25',       'LATEST(RECV_COVER) >= 1.25',       '1.60',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000013', 'ac000000-0000-0000-0000-000000000013', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.30',             'LATEST(DSCR) >= 1.30',             '1.55',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000014', 'ac000000-0000-0000-0000-000000000014', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 3.75',     'LATEST(NET_LEVERAGE) <= 3.75',     '3.20',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000015', 'ac000000-0000-0000-0000-000000000015', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 3.00',   'LATEST(INTEREST_COVER) >= 3.00',   '4.10',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000016', 'ac000000-0000-0000-0000-000000000016', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(CRUDE_OUTPUT) >= 6.0',      'LATEST(CRUDE_OUTPUT) >= 6.0',      '7.2',   'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000017', 'ac000000-0000-0000-0000-000000000017', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.30',             'LATEST(DSCR) >= 1.30',             '1.42',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000018', 'ac000000-0000-0000-0000-000000000018', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 3.75',     'LATEST(NET_LEVERAGE) <= 3.75',     '4.10',  'number',  'breached',  NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000019', 'ac000000-0000-0000-0000-000000000019', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 3.00',   'LATEST(INTEREST_COVER) >= 3.00',   '3.60',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000020', 'ac000000-0000-0000-0000-000000000020', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(CRUDE_OUTPUT) >= 6.0',      'LATEST(CRUDE_OUTPUT) >= 6.0',      '6.8',   'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000021', 'ac000000-0000-0000-0000-000000000021', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.40',             'LATEST(DSCR) >= 1.40',             '1.68',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000022', 'ac000000-0000-0000-0000-000000000022', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 4.00',     'LATEST(NET_LEVERAGE) <= 4.00',     '3.55',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000023', 'ac000000-0000-0000-0000-000000000023', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 3.50',   'LATEST(INTEREST_COVER) >= 3.50',   '4.40',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000024', 'ac000000-0000-0000-0000-000000000024', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(RECV_COVER) >= 1.25',       'LATEST(RECV_COVER) >= 1.25',       '1.48',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000025', 'ac000000-0000-0000-0000-000000000025', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.40',             'LATEST(DSCR) >= 1.40',             '1.28',  'number',  'breached',  NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000026', 'ac000000-0000-0000-0000-000000000026', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 4.00',     'LATEST(NET_LEVERAGE) <= 4.00',     '3.70',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000027', 'ac000000-0000-0000-0000-000000000027', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 3.50',   'LATEST(INTEREST_COVER) >= 3.50',   '3.95',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000028', 'ac000000-0000-0000-0000-000000000028', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(RECV_COVER) >= 1.25',       'LATEST(RECV_COVER) >= 1.25',       '1.35',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000029', 'ac000000-0000-0000-0000-000000000029', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.50',             'LATEST(DSCR) >= 1.50',             '1.90',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000030', 'ac000000-0000-0000-0000-000000000030', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 3.00',     'LATEST(NET_LEVERAGE) <= 3.00',     '2.40',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000031', 'ac000000-0000-0000-0000-000000000031', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 4.00',   'LATEST(INTEREST_COVER) >= 4.00',   '5.50',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000032', 'ac000000-0000-0000-0000-000000000032', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(TANGIBLE_NET_WORTH) >= 45000','LATEST(TANGIBLE_NET_WORTH) >= 45000','71000','number','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000033', 'ac000000-0000-0000-0000-000000000033', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.50',             'LATEST(DSCR) >= 1.50',             '2.05',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000034', 'ac000000-0000-0000-0000-000000000034', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 3.00',     'LATEST(NET_LEVERAGE) <= 3.00',     '2.30',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000035', 'ac000000-0000-0000-0000-000000000035', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 4.00',   'LATEST(INTEREST_COVER) >= 4.00',   '5.80',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000036', 'ac000000-0000-0000-0000-000000000036', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(TANGIBLE_NET_WORTH) >= 45000','LATEST(TANGIBLE_NET_WORTH) >= 45000','69000','number','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000037', 'ac000000-0000-0000-0000-000000000037', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.50',             'LATEST(DSCR) >= 1.50',             '1.78',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000038', 'ac000000-0000-0000-0000-000000000038', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 2.50',     'LATEST(NET_LEVERAGE) <= 2.50',     '1.95',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000039', 'ac000000-0000-0000-0000-000000000039', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 4.00',   'LATEST(INTEREST_COVER) >= 4.00',   '6.30',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000040', 'ac000000-0000-0000-0000-000000000040', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(EXPORT_LICENCE_VALID) == true','LATEST(EXPORT_LICENCE_VALID) == true','true','boolean','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000041', 'ac000000-0000-0000-0000-000000000041', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.50',             'LATEST(DSCR) >= 1.50',             '1.82',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000042', 'ac000000-0000-0000-0000-000000000042', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 2.50',     'LATEST(NET_LEVERAGE) <= 2.50',     '2.10',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000043', 'ac000000-0000-0000-0000-000000000043', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 4.00',   'LATEST(INTEREST_COVER) >= 4.00',   '5.90',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000044', 'ac000000-0000-0000-0000-000000000044', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(EXPORT_LICENCE_VALID) == true','LATEST(EXPORT_LICENCE_VALID) == true','true','boolean','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000045', 'ac000000-0000-0000-0000-000000000045', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 3.75',     'LATEST(NET_LEVERAGE) <= 3.75',     '3.40',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000046', 'ac000000-0000-0000-0000-000000000046', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.30',             'LATEST(DSCR) >= 1.30',             '1.52',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000047', 'ac000000-0000-0000-0000-000000000047', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 3.00',   'LATEST(INTEREST_COVER) >= 3.00',   '3.85',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000048', 'ac000000-0000-0000-0000-000000000048', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(CRUDE_OUTPUT) >= 6.0',      'LATEST(CRUDE_OUTPUT) >= 6.0',      '6.5',   'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000049', 'ac000000-0000-0000-0000-000000000049', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 3.75',     'LATEST(NET_LEVERAGE) <= 3.75',     '3.60',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000050', 'ac000000-0000-0000-0000-000000000050', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.30',             'LATEST(DSCR) >= 1.30',             '1.48',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000051', 'ac000000-0000-0000-0000-000000000051', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 3.00',   'LATEST(INTEREST_COVER) >= 3.00',   '3.40',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000052', 'ac000000-0000-0000-0000-000000000052', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(CO2_INTENSITY) <= 2.10',    'LATEST(CO2_INTENSITY) <= 2.10',    '2.24',  'number',  'breached',  NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000053', 'ac000000-0000-0000-0000-000000000053', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.50',             'LATEST(DSCR) >= 1.50',             '2.10',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000054', 'ac000000-0000-0000-0000-000000000054', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 2.00',     'LATEST(NET_LEVERAGE) <= 2.00',     '1.55',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000055', 'ac000000-0000-0000-0000-000000000055', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 5.00',   'LATEST(INTEREST_COVER) >= 5.00',   '7.20',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000056', 'ac000000-0000-0000-0000-000000000056', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(TANGIBLE_NET_WORTH) >= 45000','LATEST(TANGIBLE_NET_WORTH) >= 45000','82000','number','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000057', 'ac000000-0000-0000-0000-000000000057', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(DSCR) >= 1.40',             'LATEST(DSCR) >= 1.40',             '1.66',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000058', 'ac000000-0000-0000-0000-000000000058', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(NET_LEVERAGE) <= 2.50',     'LATEST(NET_LEVERAGE) <= 2.50',     '2.05',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000059', 'ac000000-0000-0000-0000-000000000059', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(INTEREST_COVER) >= 4.00',   'LATEST(INTEREST_COVER) >= 4.00',   '5.10',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000060', 'ac000000-0000-0000-0000-000000000060', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'LATEST(RECV_COVER) >= 1.25',       'LATEST(RECV_COVER) >= 1.25',       '1.42',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0');
-- =====================================================================================
-- 9. cov_monitoring_schedule — one Quartz binding per applied covenant (quarterly)
-- =====================================================================================
INSERT INTO cov_monitoring_schedule
    (id, applied_covenant_id, quartz_job_key, cron_expression, next_run_at, last_run_at, status,
     created_date, created_by, modified_date, modified_by, version)
SELECT REPLACE(ac.id, 'ac000000', '5c000000'),
       ac.id,
       CONCAT('assessment-', ac.id),
       '0 0 2 1 1,4,7,10 ?',
       '2026-06-30 02:00:00',
       '2026-04-01 02:05:00',
       'scheduled',
       '2025-04-01 10:00:00', 'seed', '2026-04-01 02:05:00', 'system', '0'
FROM cov_applied_covenant ac;


-- =====================================================================================
-- 10. cov_covenant_recommendation — each applied covenant was an ACCEPTED recommendation
-- =====================================================================================
INSERT INTO cov_covenant_recommendation
    (id, cp_id, covenant_master_id, match_reason, status, created_date, modified_date)
SELECT REPLACE(ac.id, 'ac000000', '4d000000'),
       ac.cp_id,
       ac.covenant_master_id,
       'Matched on business segment / industry eligibility.',
       'accepted',
       '2025-03-25 09:00:00',
       '2025-04-01 10:00:00'
FROM cov_applied_covenant ac;


-- =====================================================================================
-- 11. cov_applied_covenant_audit — activation (status) trail for every applied covenant
-- =====================================================================================
INSERT INTO cov_applied_covenant_audit
    (id, applied_covenant_id, change_type, old_value, new_value, changed_date, changed_by)
SELECT REPLACE(ac.id, 'ac000000', '9a000000'),
       ac.id, 'status', NULL, 'active', '2025-04-01 10:00:00', 'seed'
FROM cov_applied_covenant ac;


-- =====================================================================================
-- 12. cov_expression_change_log — a couple of master expression edits
-- =====================================================================================
INSERT INTO cov_expression_change_log
    (id, covenant_id, old_expression, new_expression, changed_date, changed_by) VALUES
    ('ec000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001',
     'LATEST(DSCR) >= 1.40', 'LATEST(DSCR) >= 1.50', '2025-02-01 11:00:00', 'covenant-admin'),
    ('ec000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000005',
     'LATEST(CO2_INTENSITY) <= 2.20', 'LATEST(CO2_INTENSITY) <= 2.10', '2025-02-01 11:05:00', 'covenant-admin');


-- =====================================================================================
-- 13. cov_threshold_audit — provenance for the USER / ML threshold overrides
--     applied 7 (user), 13 (user), 18 (ml), 52 (ml). ml rows get a recommended + applied pair.
-- =====================================================================================
INSERT INTO cov_threshold_audit
    (id, cp_id, covenant_master_id, applied_covenant_id, source, event,
     old_expression, new_expression, confidence, rationale, changed_by, changed_date) VALUES
    ('7a000000-0000-0000-0000-000000000001', 'CP-1002', 'c0000000-0000-0000-0000-000000000003', 'ac000000-0000-0000-0000-000000000007', 'user', 'applied',     'LATEST(INTEREST_COVER) >= 3.50', 'LATEST(INTEREST_COVER) >= 4.00', NULL,   'RM tightened interest-cover floor at sanction.', 'rm.user',  '2025-04-01 10:00:00'),
    ('7a000000-0000-0000-0000-000000000002', 'CP-1004', 'c0000000-0000-0000-0000-000000000001', 'ac000000-0000-0000-0000-000000000013', 'user', 'applied',     'LATEST(DSCR) >= 1.50',           'LATEST(DSCR) >= 1.30',           NULL,   'Negotiated DSCR relief for ramp-up period.',     'rm.user',  '2025-04-01 10:00:00'),
    ('7a000000-0000-0000-0000-000000000003', 'CP-1005', 'c0000000-0000-0000-0000-000000000002', 'ac000000-0000-0000-0000-000000000018', 'ml',   'recommended', 'LATEST(NET_LEVERAGE) <= 3.50',   'LATEST(NET_LEVERAGE) <= 3.75',   0.8200, 'Peer-calibrated leverage ceiling.',              'ml-model', '2025-03-28 04:00:00'),
    ('7a000000-0000-0000-0000-000000000004', 'CP-1005', 'c0000000-0000-0000-0000-000000000002', 'ac000000-0000-0000-0000-000000000018', 'ml',   'applied',     'LATEST(NET_LEVERAGE) <= 3.50',   'LATEST(NET_LEVERAGE) <= 3.75',   0.8200, 'Accepted ML leverage recommendation.',           'rm.user',  '2025-04-01 10:00:00'),
    ('7a000000-0000-0000-0000-000000000005', 'CP-1013', 'c0000000-0000-0000-0000-000000000005', 'ac000000-0000-0000-0000-000000000052', 'ml',   'recommended', 'LATEST(CO2_INTENSITY) <= 2.20',  'LATEST(CO2_INTENSITY) <= 2.10',  0.7600, 'SLT trajectory tightened per decarb plan.',      'ml-model', '2025-03-28 04:00:00'),
    ('7a000000-0000-0000-0000-000000000006', 'CP-1013', 'c0000000-0000-0000-0000-000000000005', 'ac000000-0000-0000-0000-000000000052', 'ml',   'applied',     'LATEST(CO2_INTENSITY) <= 2.20',  'LATEST(CO2_INTENSITY) <= 2.10',  0.7600, 'Accepted ML SLT recommendation.',                'rm.user',  '2025-04-01 10:00:00');


-- =====================================================================================
-- 14. cov_notification_audit — one daily breach-run call covering the 4 breach CPs
-- =====================================================================================
INSERT INTO cov_notification_audit
    (id, run_id, email_to, email_cc, cp_ids, request_body, response_body, http_status,
     outcome, error_message, duration_ms, changed_by, changed_date) VALUES
    ('71000000-0000-0000-0000-000000000001', 'run-2026-04-01', 'rm@bank.example', 'wrm@bank.example',
     'CP-1002,CP-1005,CP-1007,CP-1013',
     '{"runId":"run-2026-04-01","breachCount":4}',
     '{"status":"accepted","messageId":"msg-9001"}',
     200, 'success', NULL, 412, 'system', '2026-04-01 06:00:00');


-- =====================================================================================
-- 15. cov_covenant_disposition — RM/AI dispositions on the four breaches
--     applied 7 (CP-1002) waiver/user, 18 (CP-1005) extension/user,
--     25 (CP-1007) waiver/user, 52 (CP-1013) extension/ai.
-- =====================================================================================
INSERT INTO cov_covenant_disposition
    (id, cp_id, applied_covenant_id, assessment_id, status, extension_date, status_source, note,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('d1000000-0000-0000-0000-000000000001', 'CP-1002', 'ac000000-0000-0000-0000-000000000007', 'a5000000-0000-0000-0000-000000000007', 'waiver',    NULL,         'user', 'One-time waiver; interest cover expected to recover next quarter.', '2026-04-02 09:00:00','rm.user','2026-04-02 09:00:00','rm.user','0'),
    ('d1000000-0000-0000-0000-000000000002', 'CP-1005', 'ac000000-0000-0000-0000-000000000018', 'a5000000-0000-0000-0000-000000000018', 'extension', '2026-09-30', 'user', 'Extension granted pending deleveraging plan.',                      '2026-04-02 09:10:00','rm.user','2026-04-02 09:10:00','rm.user','0'),
    ('d1000000-0000-0000-0000-000000000003', 'CP-1007', 'ac000000-0000-0000-0000-000000000025', 'a5000000-0000-0000-0000-000000000025', 'waiver',    NULL,         'user', 'Waived; DSCR shortfall driven by one-off capex timing.',            '2026-04-02 09:20:00','rm.user','2026-04-02 09:20:00','rm.user','0'),
    ('d1000000-0000-0000-0000-000000000004', 'CP-1013', 'ac000000-0000-0000-0000-000000000052', 'a5000000-0000-0000-0000-000000000052', 'extension', '2026-12-31', 'ai',   'AI-suggested extension aligned to green-steel transition roadmap.', '2026-04-02 09:30:00','rm.user','2026-04-02 09:30:00','rm.user','0');


-- =====================================================================================
-- 16. cov_covenant_status_audit — append-only trail for each disposition (breached -> new)
-- =====================================================================================
INSERT INTO cov_covenant_status_audit
    (id, cp_id, applied_covenant_id, assessment_id, old_status, new_status,
     old_extension_date, new_extension_date, source, rationale, changed_by, changed_date) VALUES
    ('5a000000-0000-0000-0000-000000000001', 'CP-1002', 'ac000000-0000-0000-0000-000000000007', 'a5000000-0000-0000-0000-000000000007', 'breached', 'waiver',    NULL, NULL,         'user', 'One-time waiver; interest cover expected to recover next quarter.', 'rm.user', '2026-04-02 09:00:00'),
    ('5a000000-0000-0000-0000-000000000002', 'CP-1005', 'ac000000-0000-0000-0000-000000000018', 'a5000000-0000-0000-0000-000000000018', 'breached', 'extension', NULL, '2026-09-30', 'user', 'Extension granted pending deleveraging plan.',                      'rm.user', '2026-04-02 09:10:00'),
    ('5a000000-0000-0000-0000-000000000003', 'CP-1007', 'ac000000-0000-0000-0000-000000000025', 'a5000000-0000-0000-0000-000000000025', 'breached', 'waiver',    NULL, NULL,         'user', 'Waived; DSCR shortfall driven by one-off capex timing.',            'rm.user', '2026-04-02 09:20:00'),
    ('5a000000-0000-0000-0000-000000000004', 'CP-1013', 'ac000000-0000-0000-0000-000000000052', 'a5000000-0000-0000-0000-000000000052', 'breached', 'extension', NULL, '2026-12-31', 'ai',   'AI-suggested extension aligned to green-steel transition roadmap.', 'rm.user', '2026-04-02 09:30:00');


-- =====================================================================================
-- 17. cov_covenant_attachment — evidence files on the breached covenant rows
-- =====================================================================================
INSERT INTO cov_covenant_attachment
    (id, cp_id, applied_covenant_id, file_id, file_name, uploaded_by, uploaded_at) VALUES
    ('a7000000-0000-0000-0000-000000000001', 'CP-1002', 'ac000000-0000-0000-0000-000000000007', 'file-cp1002-ic-01',  'CP-1002-interest-cover-note.pdf', 'rm.user', '2026-04-02 08:55:00'),
    ('a7000000-0000-0000-0000-000000000002', 'CP-1005', 'ac000000-0000-0000-0000-000000000018', 'file-cp1005-nl-01',  'CP-1005-deleveraging-plan.pdf',   'rm.user', '2026-04-02 09:05:00'),
    ('a7000000-0000-0000-0000-000000000003', 'CP-1007', 'ac000000-0000-0000-0000-000000000025', 'file-cp1007-dscr-01', 'CP-1007-capex-schedule.pdf',      'rm.user', '2026-04-02 09:15:00'),
    ('a7000000-0000-0000-0000-000000000004', 'CP-1013', 'ac000000-0000-0000-0000-000000000052', 'file-cp1013-co2-01',  'CP-1013-decarb-roadmap.pdf',      'rm.user', '2026-04-02 09:25:00');


-- =====================================================================================
-- 18. cov_compliance_certificate — one certificate per CP (CP-1001 .. CP-1015)
-- =====================================================================================
INSERT INTO cov_compliance_certificate
    (id, cp_id, file_id, file_name, uploaded_by, uploaded_at) VALUES
    ('cc000000-0000-0000-0000-000000000001', 'CP-1001', 'cert-CP-1001', 'CP-1001-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000002', 'CP-1002', 'cert-CP-1002', 'CP-1002-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000003', 'CP-1003', 'cert-CP-1003', 'CP-1003-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000004', 'CP-1004', 'cert-CP-1004', 'CP-1004-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000005', 'CP-1005', 'cert-CP-1005', 'CP-1005-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000006', 'CP-1006', 'cert-CP-1006', 'CP-1006-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000007', 'CP-1007', 'cert-CP-1007', 'CP-1007-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000008', 'CP-1008', 'cert-CP-1008', 'CP-1008-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000009', 'CP-1009', 'cert-CP-1009', 'CP-1009-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000010', 'CP-1010', 'cert-CP-1010', 'CP-1010-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000011', 'CP-1011', 'cert-CP-1011', 'CP-1011-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000012', 'CP-1012', 'cert-CP-1012', 'CP-1012-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000013', 'CP-1013', 'cert-CP-1013', 'CP-1013-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000014', 'CP-1014', 'cert-CP-1014', 'CP-1014-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00'),
    ('cc000000-0000-0000-0000-000000000015', 'CP-1015', 'cert-CP-1015', 'CP-1015-compliance-certificate.pdf', 'rm.user', '2026-04-02 07:00:00');


-- =====================================================================================
-- End of consolidated data script.
-- =====================================================================================
