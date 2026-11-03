-- =====================================================================================
-- V10 (DEV only) — demo data seed.
-- =====================================================================================
-- Loaded only under the `dev` profile (classpath:db/demo), AFTER the prod migrations
-- (V1 baseline + V2 reference/settings seed).
--
-- Contents:
--   (i)  cp_metrics_demo — the external simulated MSSQL financials source table
--        (registered as the "Demo MSSQL Financials" data source). NOT a cov_ table and
--        deliberately NOT part of the cov_ schema convention — preserved as-is.
--   (ii) sample cov_* data (sections 3-19 of db/all_data.sql): data sources, measures,
--        covenant masters, applied covenants, assessments and the full audit/disposition/
--        attachment trail for CP-1001 .. CP-1015. Sections 1-2 (reference_data + reporting_
--        setting) are intentionally omitted — they are seeded by prod migration V2.
-- =====================================================================================


-- =====================================================================================
-- cp_metrics_demo — external simulated MSSQL financials source (columnar time-series).
-- =====================================================================================
CREATE TABLE cp_metrics_demo (
    cp_id                 VARCHAR(32) NOT NULL,
    as_of_date            DATE        NOT NULL,
    dscr                  DECIMAL(18,4),
    net_leverage          DECIMAL(18,4),
    interest_cover        DECIMAL(18,4),
    tnw_inr_cr            DECIMAL(18,2),
    co2_intensity         DECIMAL(18,4),
    crude_output_mt       DECIMAL(18,4),
    recv_cover            DECIMAL(18,4),
    export_licence_valid  BIT,
    CONSTRAINT pk_cp_metrics_demo PRIMARY KEY (cp_id, as_of_date)
);

-- Columnar time-series feeding the eight seeded measures (source_column = physical column):
--   dscr, net_leverage, interest_cover, tnw_inr_cr, co2_intensity, crude_output_mt,
--   recv_cover, export_licence_valid. LATEST()/CURRENT() reads the most recent as_of_date row.
-- Two quarters per CP (2025-12-31, 2026-03-31); the 2026-03-31 row drives LATEST(). Values keep
-- every applied covenant compliant EXCEPT the four seeded breaches, which decline into breach in
-- the latest quarter: CP-1002 interest_cover (ac07), CP-1005 net_leverage (ac18),
-- CP-1007 dscr (ac25) and CP-1013 co2_intensity (ac52).
INSERT INTO cp_metrics_demo (cp_id, as_of_date, dscr, net_leverage, interest_cover, tnw_inr_cr, co2_intensity, crude_output_mt, recv_cover, export_licence_valid) VALUES
    ('CP-1001', '2025-12-31', 1.80, 2.95, 5.10, 60500.00, 1.88, 7.10, 1.52, 1),
    ('CP-1001', '2026-03-31', 1.85, 2.90, 5.20, 62000.00, 1.85, 7.20, 1.55, 1),
    ('CP-1002', '2025-12-31', 1.80, 3.05, 4.10, 57000.00, 1.88, 7.10, 1.52, 1),
    ('CP-1002', '2026-03-31', 1.78, 3.10, 3.40, 58000.00, 1.85, 7.20, 1.55, 1),
    ('CP-1003', '2025-12-31', 1.74, 3.00, 4.50, 54000.00, 1.88, 7.10, 1.45, 1),
    ('CP-1003', '2026-03-31', 1.72, 3.05, 4.60, 55000.00, 1.85, 7.20, 1.48, 1),
    ('CP-1004', '2025-12-31', 1.58, 3.35, 3.70, 51000.00, 1.98, 6.70, 1.38, 1),
    ('CP-1004', '2026-03-31', 1.55, 3.40, 3.80, 52000.00, 1.95, 6.80, 1.40, 1),
    ('CP-1005', '2025-12-31', 1.50, 3.60, 3.70, 49000.00, 1.98, 6.60, 1.38, 1),
    ('CP-1005', '2026-03-31', 1.48, 3.95, 3.60, 50000.00, 1.95, 6.50, 1.40, 1),
    ('CP-1006', '2025-12-31', 1.62, 3.65, 3.95, 47000.00, 1.92, 6.55, 1.43, 1),
    ('CP-1006', '2026-03-31', 1.60, 3.70, 3.90, 48000.00, 1.90, 6.60, 1.45, 1),
    ('CP-1007', '2025-12-31', 1.45, 3.75, 3.75, 46000.00, 1.92, 6.55, 1.40, 1),
    ('CP-1007', '2026-03-31', 1.28, 3.80, 3.70, 47000.00, 1.90, 6.60, 1.42, 1),
    ('CP-1008', '2025-12-31', 1.88, 2.65, 4.90, 58500.00, 1.88, 6.90, 1.52, 1),
    ('CP-1008', '2026-03-31', 1.90, 2.60, 5.00, 60000.00, 1.85, 7.00, 1.55, 1),
    ('CP-1009', '2025-12-31', 1.84, 2.75, 4.70, 55500.00, 1.88, 6.90, 1.52, 1),
    ('CP-1009', '2026-03-31', 1.82, 2.70, 4.80, 57000.00, 1.85, 7.00, 1.55, 1),
    ('CP-1010', '2025-12-31', 1.92, 2.25, 5.30, 62500.00, 1.82, 7.40, 1.58, 1),
    ('CP-1010', '2026-03-31', 1.95, 2.20, 5.40, 64000.00, 1.80, 7.50, 1.60, 1),
    ('CP-1011', '2025-12-31', 1.86, 2.35, 5.00, 59500.00, 1.82, 7.40, 1.58, 1),
    ('CP-1011', '2026-03-31', 1.88, 2.30, 5.10, 61000.00, 1.80, 7.50, 1.60, 1),
    ('CP-1012', '2025-12-31', 1.54, 3.40, 3.85, 52000.00, 1.98, 6.80, 1.43, 1),
    ('CP-1012', '2026-03-31', 1.52, 3.45, 3.90, 53000.00, 1.95, 6.90, 1.45, 1),
    ('CP-1013', '2025-12-31', 1.52, 3.45, 3.75, 50000.00, 2.05, 6.60, 1.43, 1),
    ('CP-1013', '2026-03-31', 1.50, 3.50, 3.70, 51000.00, 2.35, 6.70, 1.45, 1),
    ('CP-1014', '2025-12-31', 2.02, 1.80, 5.70, 66500.00, 1.78, 7.70, 1.62, 1),
    ('CP-1014', '2026-03-31', 2.05, 1.75, 5.80, 68000.00, 1.75, 7.80, 1.65, 1),
    ('CP-1015', '2025-12-31', 1.72, 2.30, 4.40, 54500.00, 1.88, 7.20, 1.48, 1),
    ('CP-1015', '2026-03-31', 1.70, 2.25, 4.50, 56000.00, 1.85, 7.30, 1.50, 1);


-- =====================================================================================
-- cp_ratios_kv_demo — external simulated MSSQL source in ROW-LEVEL / KEY-VALUE (EAV) shape.
-- One row per (cp_id, as_of_date, metric_code) with a single shared value column. This is the
-- "row level data fetch" layout: measures map to a metric_code (source_code) rather than to a
-- dedicated physical column. Registered as the "Demo MSSQL Ratios (Row-Level)" data source and,
-- like cp_metrics_demo, is deliberately NOT a cov_ table (it simulates an external source).
-- =====================================================================================
CREATE TABLE cp_ratios_kv_demo (
    cp_id        VARCHAR(32) NOT NULL,
    as_of_date   DATE        NOT NULL,
    metric_code  VARCHAR(64) NOT NULL,
    metric_value DECIMAL(18,4),
    CONSTRAINT pk_cp_ratios_kv_demo PRIMARY KEY (cp_id, as_of_date, metric_code)
);

-- Latest-quarter (2026-03-31) values for four ratio measures keyed by metric_code:
--   CURRENT_RATIO (>= 1.20), QUICK_RATIO (>= 1.00), DEBT_EQUITY (<= 2.00), DEBT_EBITDA (<= 4.00).
-- Values keep every CP compliant so the row-level covenants assess cleanly when applied.
INSERT INTO cp_ratios_kv_demo (cp_id, as_of_date, metric_code, metric_value) VALUES
    ('CP-1001', '2026-03-31', 'CURRENT_RATIO', 1.85), ('CP-1001', '2026-03-31', 'QUICK_RATIO', 1.40), ('CP-1001', '2026-03-31', 'DEBT_EQUITY', 1.10), ('CP-1001', '2026-03-31', 'DEBT_EBITDA', 2.90),
    ('CP-1002', '2026-03-31', 'CURRENT_RATIO', 1.60), ('CP-1002', '2026-03-31', 'QUICK_RATIO', 1.20), ('CP-1002', '2026-03-31', 'DEBT_EQUITY', 1.30), ('CP-1002', '2026-03-31', 'DEBT_EBITDA', 3.10),
    ('CP-1003', '2026-03-31', 'CURRENT_RATIO', 1.55), ('CP-1003', '2026-03-31', 'QUICK_RATIO', 1.15), ('CP-1003', '2026-03-31', 'DEBT_EQUITY', 1.35), ('CP-1003', '2026-03-31', 'DEBT_EBITDA', 3.05),
    ('CP-1004', '2026-03-31', 'CURRENT_RATIO', 1.40), ('CP-1004', '2026-03-31', 'QUICK_RATIO', 1.05), ('CP-1004', '2026-03-31', 'DEBT_EQUITY', 1.70), ('CP-1004', '2026-03-31', 'DEBT_EBITDA', 3.40),
    ('CP-1005', '2026-03-31', 'CURRENT_RATIO', 1.35), ('CP-1005', '2026-03-31', 'QUICK_RATIO', 1.02), ('CP-1005', '2026-03-31', 'DEBT_EQUITY', 1.85), ('CP-1005', '2026-03-31', 'DEBT_EBITDA', 3.95),
    ('CP-1006', '2026-03-31', 'CURRENT_RATIO', 1.45), ('CP-1006', '2026-03-31', 'QUICK_RATIO', 1.08), ('CP-1006', '2026-03-31', 'DEBT_EQUITY', 1.60), ('CP-1006', '2026-03-31', 'DEBT_EBITDA', 3.70),
    ('CP-1007', '2026-03-31', 'CURRENT_RATIO', 1.30), ('CP-1007', '2026-03-31', 'QUICK_RATIO', 1.00), ('CP-1007', '2026-03-31', 'DEBT_EQUITY', 1.75), ('CP-1007', '2026-03-31', 'DEBT_EBITDA', 3.80),
    ('CP-1008', '2026-03-31', 'CURRENT_RATIO', 1.90), ('CP-1008', '2026-03-31', 'QUICK_RATIO', 1.45), ('CP-1008', '2026-03-31', 'DEBT_EQUITY', 1.05), ('CP-1008', '2026-03-31', 'DEBT_EBITDA', 2.60),
    ('CP-1009', '2026-03-31', 'CURRENT_RATIO', 1.80), ('CP-1009', '2026-03-31', 'QUICK_RATIO', 1.35), ('CP-1009', '2026-03-31', 'DEBT_EQUITY', 1.15), ('CP-1009', '2026-03-31', 'DEBT_EBITDA', 2.70),
    ('CP-1010', '2026-03-31', 'CURRENT_RATIO', 1.95), ('CP-1010', '2026-03-31', 'QUICK_RATIO', 1.50), ('CP-1010', '2026-03-31', 'DEBT_EQUITY', 0.95), ('CP-1010', '2026-03-31', 'DEBT_EBITDA', 2.20),
    ('CP-1011', '2026-03-31', 'CURRENT_RATIO', 1.88), ('CP-1011', '2026-03-31', 'QUICK_RATIO', 1.42), ('CP-1011', '2026-03-31', 'DEBT_EQUITY', 1.00), ('CP-1011', '2026-03-31', 'DEBT_EBITDA', 2.30),
    ('CP-1012', '2026-03-31', 'CURRENT_RATIO', 1.42), ('CP-1012', '2026-03-31', 'QUICK_RATIO', 1.06), ('CP-1012', '2026-03-31', 'DEBT_EQUITY', 1.68), ('CP-1012', '2026-03-31', 'DEBT_EBITDA', 3.45),
    ('CP-1013', '2026-03-31', 'CURRENT_RATIO', 1.38), ('CP-1013', '2026-03-31', 'QUICK_RATIO', 1.04), ('CP-1013', '2026-03-31', 'DEBT_EQUITY', 1.72), ('CP-1013', '2026-03-31', 'DEBT_EBITDA', 3.50),
    ('CP-1014', '2026-03-31', 'CURRENT_RATIO', 2.05), ('CP-1014', '2026-03-31', 'QUICK_RATIO', 1.60), ('CP-1014', '2026-03-31', 'DEBT_EQUITY', 0.85), ('CP-1014', '2026-03-31', 'DEBT_EBITDA', 1.75),
    ('CP-1015', '2026-03-31', 'CURRENT_RATIO', 1.70), ('CP-1015', '2026-03-31', 'QUICK_RATIO', 1.28), ('CP-1015', '2026-03-31', 'DEBT_EQUITY', 1.25), ('CP-1015', '2026-03-31', 'DEBT_EBITDA', 2.25);


-- =====================================================================================
-- 3. cov_data_source — MSSQL columnar financials + MSSQL row-level ratios + REST attributes
-- =====================================================================================
INSERT INTO cov_data_source
    (id, name, type, description, connection_config_json, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('d5000000-0000-0000-0000-000000000001', 'Demo MSSQL Financials', 'mssql',
        'Quarterly borrower financial metrics (columnar); in dev this simulated external source points at the app H2 file DB (AUTO_SERVER) so cp_metrics_demo resolves end-to-end.',
        '{"jdbcUrl":"jdbc:h2:file:./data/covenant;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE;AUTO_SERVER=TRUE","username":"sa","password":"","tableName":"cp_metrics_demo"}',
        1, '2025-01-05 09:00:00', 'seed', '2025-01-05 09:00:00', 'seed', '0'),
    ('d5000000-0000-0000-0000-000000000002', 'CP Attributes REST API', 'rest',
        'Credit-proposal attribute service (eligibility + segment). API-based source: measures are read from the REST payload, so covenants on it are assessed manually (no JDBC fetch).',
        '{"baseUrl":"http://localhost:9311/api/v1/credit-proposals"}',
        1, '2025-01-05 09:00:00', 'seed', '2025-01-05 09:00:00', 'seed', '0'),
    ('d5000000-0000-0000-0000-000000000003', 'Demo MSSQL Ratios (Row-Level)', 'mssql',
        'Row-level / key-value (EAV) financial ratios: one row per (cp, as-of, metric_code). In dev this simulated external source points at the app H2 file DB (AUTO_SERVER) so cp_ratios_kv_demo resolves end-to-end.',
        '{"jdbcUrl":"jdbc:h2:file:./data/covenant;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE;AUTO_SERVER=TRUE","username":"sa","password":"","tableName":"cp_ratios_kv_demo"}',
        1, '2025-01-05 09:00:00', 'seed', '2025-01-05 09:00:00', 'seed', '0');


-- =====================================================================================
-- 4. cov_data_source_table — the columnar metrics table under the MSSQL source
-- =====================================================================================
INSERT INTO cov_data_source_table
    (id, data_source_id, name, schema_name, table_name, entity_key_column, as_of_column,
     table_layout, code_column, value_column, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('d7000000-0000-0000-0000-000000000001', 'd5000000-0000-0000-0000-000000000001',
        'CP Quarterly Metrics', NULL, 'cp_metrics_demo', 'cp_id', 'as_of_date',
        'columnar', NULL, NULL, 1,
        '2025-01-05 09:10:00', 'seed', '2025-01-05 09:10:00', 'seed', '0'),
    -- Row-level / key-value table: many measures share one value column, keyed by metric_code.
    ('d7000000-0000-0000-0000-000000000002', 'd5000000-0000-0000-0000-000000000003',
        'CP Ratios (Key-Value)', NULL, 'cp_ratios_kv_demo', 'cp_id', 'as_of_date',
        'key_value', 'metric_code', 'metric_value', 1,
        '2025-01-05 09:10:00', 'seed', '2025-01-05 09:10:00', 'seed', '0'),
    -- REST/API attribute table: shape is nominal (covenants on it are manual; no JDBC fetch runs).
    ('d7000000-0000-0000-0000-000000000003', 'd5000000-0000-0000-0000-000000000002',
        'CP Attributes (REST)', NULL, 'attributes', 'cpId', 'asOfDate',
        'columnar', NULL, NULL, 1,
        '2025-01-05 09:10:00', 'seed', '2025-01-05 09:10:00', 'seed', '0');


-- =====================================================================================
-- 5. cov_measure — reusable measures referenced by covenant formulas (code-keyed)
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
        'd7000000-0000-0000-0000-000000000001', 'export_licence_valid', NULL, 'current', 'boolean', NULL, 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    -- Row-level (KEY_VALUE) measures: mapped by source_code (metric_code), no source_column.
    ('3e000000-0000-0000-0000-000000000009', 'CURRENT_RATIO', 'Current Ratio', 'Current assets / current liabilities (row-level source).',
        'd7000000-0000-0000-0000-000000000002', NULL, 'CURRENT_RATIO', 'latest', 'number', 'x', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    ('3e000000-0000-0000-0000-000000000010', 'QUICK_RATIO', 'Quick Ratio', 'Quick (acid-test) ratio (row-level source).',
        'd7000000-0000-0000-0000-000000000002', NULL, 'QUICK_RATIO', 'latest', 'number', 'x', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    ('3e000000-0000-0000-0000-000000000011', 'DEBT_EQUITY', 'Debt to Equity', 'Total debt / tangible net worth (row-level source).',
        'd7000000-0000-0000-0000-000000000002', NULL, 'DEBT_EQUITY', 'latest', 'number', 'x', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    ('3e000000-0000-0000-0000-000000000012', 'DEBT_EBITDA', 'Total Debt to EBITDA', 'Total debt / EBITDA (row-level source).',
        'd7000000-0000-0000-0000-000000000002', NULL, 'DEBT_EBITDA', 'latest', 'number', 'x', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    -- REST/API measures: sourced from the CP Attributes REST payload; used only by MANUAL covenants.
    ('3e000000-0000-0000-0000-000000000013', 'FACILITY_AMOUNT', 'Facility Amount', 'Sanctioned facility amount (INR crore) from the CP Attributes REST API.',
        'd7000000-0000-0000-0000-000000000003', 'facilityAmount', NULL, 'current', 'number', 'INR cr', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0'),
    ('3e000000-0000-0000-0000-000000000014', 'TENOR_MONTHS', 'Facility Tenor', 'Facility tenor in months from the CP Attributes REST API.',
        'd7000000-0000-0000-0000-000000000003', 'tenorMonths', NULL, 'current', 'number', 'months', 1, '2025-01-06 09:00:00','seed','2025-01-06 09:00:00','seed','0');


-- =====================================================================================
-- 6. cov_covenant_master — library covenant definitions used across the CPs
-- =====================================================================================
INSERT INTO cov_covenant_master
    (id, name, category, description, business_segments, condition_expression, referenced_measures,
     islamic_banking, industries, countries_of_risk, countries_of_incorporation,
     credit_proposal_types, legal_entity_types, facility_types, collateral_types,
     assessment_type, default_frequency, default_cron, is_custom, owner_cp_id, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'Minimum DSCR', 'financial',
        'Debt Service Coverage Ratio must stay at or above the agreed floor, tested quarterly.',
        '["CORPORATE","CONGLOMERATE","INFRASTRUCTURE"]', 'DSCR >= 1.50', '["DSCR"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000002', 'Maximum Net Leverage', 'financial',
        'Net debt to EBITDA must not exceed the agreed ceiling.',
        '["CORPORATE","CONGLOMERATE"]', 'NET_LEVERAGE <= 3.50', '["NET_LEVERAGE"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000003', 'Minimum Interest Coverage', 'financial',
        'EBITDA must cover interest expense by at least the agreed multiple.',
        '["CORPORATE","CONGLOMERATE"]', 'INTEREST_COVER >= 4.00', '["INTEREST_COVER"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000004', 'Minimum Tangible Net Worth', 'financial',
        'Tangible net worth must remain at or above the agreed floor (INR crore).',
        '["CORPORATE","CONGLOMERATE"]', 'TANGIBLE_NET_WORTH >= 45000', '["TANGIBLE_NET_WORTH"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000005', 'CO2 Intensity Cap (SLT)', 'financial',
        'Sustainability-linked target: CO2 intensity must not exceed the agreed cap (t/t crude steel).',
        '["CORPORATE"]', 'CO2_INTENSITY <= 2.10', '["CO2_INTENSITY"]',
        0, '["METALS_MINING"]', NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000006', 'Minimum Crude Steel Output', 'financial',
        'Crude steel output must remain at or above the agreed level (million tonnes).',
        '["CORPORATE"]', 'CRUDE_OUTPUT >= 6.0', '["CRUDE_OUTPUT"]',
        0, '["METALS_MINING"]', NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000007', 'Export Receivables Cover', 'financial',
        'Export receivables cover ratio must remain at or above the agreed floor.',
        '["CORPORATE","INFRASTRUCTURE"]', 'RECV_COVER >= 1.25', '["RECV_COVER"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000008', 'Export Authorisation Valid', 'non_financial',
        'A valid defence-manufacturing / export authorisation must be maintained.',
        '["CORPORATE"]', 'EXPORT_LICENCE_VALID == true', '["EXPORT_LICENCE_VALID"]',
        0, '["MANUFACTURING"]', NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-10 09:00:00','seed','2025-01-10 09:00:00','seed','0'),
    -- -----------------------------------------------------------------------------
    -- Manual (display-only) library covenants. assessment_type='manual', no measures,
    -- wildcard eligibility so they surface across CPs to restore the full library.
    -- -----------------------------------------------------------------------------
    -- Row-level (KEY_VALUE) covenants: AUTO-assessed via the "Demo MSSQL Ratios (Row-Level)" source.
    ('c0000000-0000-0000-0000-000000000009', 'Current Ratio Floor', 'financial',
        'Maintain a current ratio at or above 1.20x, tested at each reporting date (row-level source).',
        '["ALL"]', 'CURRENT_RATIO >= 1.20', '["CURRENT_RATIO"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000010', 'Quick Ratio Floor', 'financial',
        'Maintain a quick (acid-test) ratio at or above 1.00x (row-level source).',
        '["ALL"]', 'QUICK_RATIO >= 1.00', '["QUICK_RATIO"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000011', 'Debt to Equity Ceiling', 'financial',
        'Total debt to tangible net worth must not exceed 2.00x (row-level source).',
        '["ALL"]', 'DEBT_EQUITY <= 2.00', '["DEBT_EQUITY"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000012', 'Total Debt to EBITDA Ceiling', 'financial',
        'Total debt to EBITDA must not exceed 4.00x (row-level source).',
        '["ALL"]', 'DEBT_EBITDA <= 4.00', '["DEBT_EBITDA"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000013', 'Minimum Net Worth', 'financial',
        'Tangible net worth must remain at or above the sanctioned floor.',
        '["ALL"]', 'Net worth >= agreed floor', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000014', 'EBITDA Margin Floor', 'financial',
        'EBITDA margin must remain at or above 15% of net revenue.',
        '["ALL"]', 'EBITDA margin >= 15%', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000015', 'Return on Capital Employed Floor', 'financial',
        'ROCE must remain at or above 12% per annum.',
        '["ALL"]', 'ROCE >= 12%', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000016', 'Fixed Asset Coverage Ratio', 'financial',
        'Fixed asset coverage ratio must remain at or above 1.25x.',
        '["ALL"]', 'FACR >= 1.25x', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000017', 'Minimum Profit After Tax', 'financial',
        'Profit after tax must remain positive at each annual reporting date.',
        '["ALL"]', 'PAT > 0', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000018', 'Operating Profit Margin Floor', 'financial',
        'Operating profit margin must remain at or above 10%.',
        '["ALL"]', 'Operating margin >= 10%', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000019', 'Net Profit Margin Floor', 'financial',
        'Net profit margin must remain at or above 5%.',
        '["ALL"]', 'Net margin >= 5%', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000020', 'Minimum Revenue Growth', 'financial',
        'Year-on-year net revenue growth must remain at or above 5%.',
        '["ALL"]', 'Revenue growth >= 5% YoY', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000021', 'Gross Debt Ceiling', 'financial',
        'Gross borrowings must not exceed the sanctioned ceiling.',
        '["ALL"]', 'Gross debt <= agreed ceiling', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000022', 'Cash Flow Coverage Ratio', 'financial',
        'Operating cash flow must cover debt service by at least 1.10x.',
        '["ALL"]', 'Cash flow coverage >= 1.10x', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000023', 'Minimum Cash and Equivalents', 'financial',
        'Minimum cash and cash-equivalent balance must be maintained.',
        '["ALL"]', 'Cash balance >= agreed floor', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000024', 'Working Capital Floor', 'financial',
        'Net working capital must remain at or above the agreed floor.',
        '["ALL"]', 'Working capital >= agreed floor', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000025', 'Inventory Turnover Floor', 'financial',
        'Inventory turnover must remain at or above the agreed level.',
        '["ALL"]', 'Inventory turnover >= agreed level', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000026', 'Receivables Days Ceiling', 'financial',
        'Debtor days must not exceed the agreed maximum.',
        '["ALL"]', 'Receivable days <= agreed maximum', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000027', 'Payables Days Ceiling', 'financial',
        'Creditor days must not exceed the agreed maximum.',
        '["ALL"]', 'Payable days <= agreed maximum', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000028', 'Capital Expenditure Ceiling', 'financial',
        'Annual capital expenditure must not exceed the sanctioned limit without lender consent.',
        '["ALL"]', 'Capex <= sanctioned limit', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000029', 'Dividend Payout Ceiling', 'financial',
        'Dividend payout must not exceed 30% of PAT without lender consent.',
        '["ALL"]', 'Dividend payout <= 30% of PAT', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000030', 'Minimum Promoter Shareholding', 'non_financial',
        'Promoter shareholding must remain at or above the agreed minimum.',
        '["ALL"]', 'Promoter holding >= agreed minimum', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000031', 'Insurance Cover Maintained', 'non_financial',
        'Adequate insurance cover over charged assets must be maintained at all times.',
        '["ALL"]', 'Insurance cover maintained', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000032', 'Statutory Auditor Appointment', 'non_financial',
        'A statutory auditor acceptable to the lender must be appointed and maintained.',
        '["ALL"]', 'Approved statutory auditor appointed', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000033', 'Quarterly MIS Submission', 'non_financial',
        'Quarterly management information statements must be submitted within 45 days.',
        '["ALL"]', 'Quarterly MIS submitted within 45 days', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000034', 'Annual Audited Financials Submission', 'non_financial',
        'Audited annual financial statements must be submitted within 180 days of year-end.',
        '["ALL"]', 'Audited financials submitted within 180 days', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000035', 'No Change of Control', 'non_financial',
        'No change of control or management shall occur without prior lender consent.',
        '["ALL"]', 'No change of control without consent', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000036', 'Negative Pledge', 'non_financial',
        'No security over assets shall be created in favour of third parties without consent.',
        '["ALL"]', 'Negative pledge maintained', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000037', 'Environmental Clearance Valid', 'non_financial',
        'All applicable environmental clearances and consents must remain valid.',
        '["ALL"]', 'Environmental clearances valid', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000038', 'Key-Man Insurance', 'non_financial',
        'Key-man insurance over identified promoters/directors must be maintained.',
        '["ALL"]', 'Key-man insurance maintained', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000039', 'Cross-Default Clause', 'non_financial',
        'Any default under other financial indebtedness shall constitute a default hereunder.',
        '["ALL"]', 'No cross-default event', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000040', 'Maintenance of Security Cover', 'non_financial',
        'Minimum security cover over the facility must be maintained at all times.',
        '["ALL"]', 'Security cover >= agreed minimum', '[]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    -- API-based covenants: measures come from the CP Attributes REST API, so these are assessed
    -- MANUALLY (the engine has no JDBC fetch for a REST source). referenced_measures documents the link.
    ('c0000000-0000-0000-0000-000000000041', 'Facility Amount Within Sanctioned Limit', 'financial',
        'Drawn/sanctioned facility amount must stay within the approved limit (sourced from the CP Attributes REST API).',
        '["ALL"]', 'FACILITY_AMOUNT within sanctioned limit', '["FACILITY_AMOUNT"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0'),
    ('c0000000-0000-0000-0000-000000000042', 'Minimum Facility Tenor', 'financial',
        'Facility tenor must remain at or above the agreed minimum (sourced from the CP Attributes REST API).',
        '["ALL"]', 'TENOR_MONTHS >= agreed minimum', '["TENOR_MONTHS"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'manual', 'quarterly', NULL, 0, NULL, 1,
        '2025-01-12 09:00:00','seed','2025-01-12 09:00:00','seed','0');


-- =====================================================================================
-- 7. cov_credit_proposal_cache — one row per CP (matches the sample certificates)
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
-- 8. cov_applied_covenant — 4 covenants per CP
--    threshold_source: mostly master; a few user/ml to exercise provenance.
-- =====================================================================================
INSERT INTO cov_applied_covenant
    (id, cp_id, covenant_master_id, frequency, cron_expression, start_date, end_date, status,
     snapshot_json, condition_expression, condition_type, classification, threshold_source,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('ac000000-0000-0000-0000-000000000001', 'CP-1001', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000002', 'CP-1001', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 3.50',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000003', 'CP-1001', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000004', 'CP-1001', 'c0000000-0000-0000-0000-000000000004', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'TANGIBLE_NET_WORTH >= 45000','subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000005', 'CP-1002', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000006', 'CP-1002', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 3.50',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000007', 'CP-1002', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 4.00',   'subsequent','external','user',  '2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000008', 'CP-1002', 'c0000000-0000-0000-0000-000000000004', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'TANGIBLE_NET_WORTH >= 45000','subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000009', 'CP-1003', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000010', 'CP-1003', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 3.50',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000011', 'CP-1003', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000012', 'CP-1003', 'c0000000-0000-0000-0000-000000000007', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'RECV_COVER >= 1.25',       'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000013', 'CP-1004', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.30',             'subsequent','external','user',  '2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000014', 'CP-1004', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 3.75',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000015', 'CP-1004', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 3.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000016', 'CP-1004', 'c0000000-0000-0000-0000-000000000006', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'CRUDE_OUTPUT >= 6.0',      'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000017', 'CP-1005', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.30',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000018', 'CP-1005', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 3.75',     'subsequent','external','ml',    '2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000019', 'CP-1005', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 3.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000020', 'CP-1005', 'c0000000-0000-0000-0000-000000000006', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'CRUDE_OUTPUT >= 6.0',      'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000021', 'CP-1006', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.40',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000022', 'CP-1006', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 4.00',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000023', 'CP-1006', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 3.50',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000024', 'CP-1006', 'c0000000-0000-0000-0000-000000000007', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'RECV_COVER >= 1.25',       'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000025', 'CP-1007', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.40',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000026', 'CP-1007', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 4.00',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000027', 'CP-1007', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 3.50',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000028', 'CP-1007', 'c0000000-0000-0000-0000-000000000007', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'RECV_COVER >= 1.25',       'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000029', 'CP-1008', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000030', 'CP-1008', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 3.00',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000031', 'CP-1008', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000032', 'CP-1008', 'c0000000-0000-0000-0000-000000000004', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'TANGIBLE_NET_WORTH >= 45000','subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000033', 'CP-1009', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000034', 'CP-1009', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 3.00',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000035', 'CP-1009', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000036', 'CP-1009', 'c0000000-0000-0000-0000-000000000004', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'TANGIBLE_NET_WORTH >= 45000','subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000037', 'CP-1010', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000038', 'CP-1010', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 2.50',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000039', 'CP-1010', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000040', 'CP-1010', 'c0000000-0000-0000-0000-000000000008', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'EXPORT_LICENCE_VALID == true','precedent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000041', 'CP-1011', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000042', 'CP-1011', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 2.50',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000043', 'CP-1011', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000044', 'CP-1011', 'c0000000-0000-0000-0000-000000000008', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'EXPORT_LICENCE_VALID == true','precedent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000045', 'CP-1012', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 3.75',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000046', 'CP-1012', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.30',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000047', 'CP-1012', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 3.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000048', 'CP-1012', 'c0000000-0000-0000-0000-000000000006', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'CRUDE_OUTPUT >= 6.0',      'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000049', 'CP-1013', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 3.75',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000050', 'CP-1013', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.30',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000051', 'CP-1013', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 3.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000052', 'CP-1013', 'c0000000-0000-0000-0000-000000000005', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'CO2_INTENSITY <= 2.10',    'subsequent','external','ml',    '2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000053', 'CP-1014', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.50',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000054', 'CP-1014', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 2.00',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000055', 'CP-1014', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 5.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000056', 'CP-1014', 'c0000000-0000-0000-0000-000000000004', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'TANGIBLE_NET_WORTH >= 45000','subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000057', 'CP-1015', 'c0000000-0000-0000-0000-000000000001', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'DSCR >= 1.40',             'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000058', 'CP-1015', 'c0000000-0000-0000-0000-000000000002', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'NET_LEVERAGE <= 2.50',     'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000059', 'CP-1015', 'c0000000-0000-0000-0000-000000000003', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'INTEREST_COVER >= 4.00',   'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0'),
    ('ac000000-0000-0000-0000-000000000060', 'CP-1015', 'c0000000-0000-0000-0000-000000000007', 'quarterly', NULL, '2025-04-01', NULL, 'active', '{"seed":true}', 'RECV_COVER >= 1.25',       'subsequent','external','master','2025-04-01 10:00:00','seed','2026-04-01 02:05:00','system','0');


-- =====================================================================================
-- 9. cov_assessment — latest quarter run per applied covenant
--    Breaches on applied ids 07 (CP-1002 Interest Cover), 18 (CP-1005 Net Leverage),
--    25 (CP-1007 DSCR) and 52 (CP-1013 CO2 intensity SLT).
-- =====================================================================================
INSERT INTO cov_assessment
    (id, applied_covenant_id, scheduled_at, executed_at, data_snapshot_json,
     evaluated_expression, condition_expression, computed_value, computed_value_type,
     result, error_message, assessment_note, workflow_instance_id, workflow_error,
     triggered_by, triggered_by_user, status,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('a5000000-0000-0000-0000-000000000001', 'ac000000-0000-0000-0000-000000000001', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.50',             'DSCR >= 1.50',             '1.85',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000002', 'ac000000-0000-0000-0000-000000000002', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 3.50',     'NET_LEVERAGE <= 3.50',     '2.90',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000003', 'ac000000-0000-0000-0000-000000000003', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 4.00',   'INTEREST_COVER >= 4.00',   '5.20',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000004', 'ac000000-0000-0000-0000-000000000004', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'TANGIBLE_NET_WORTH >= 45000','TANGIBLE_NET_WORTH >= 45000','62000','number','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000005', 'ac000000-0000-0000-0000-000000000005', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.50',             'DSCR >= 1.50',             '1.72',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000006', 'ac000000-0000-0000-0000-000000000006', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 3.50',     'NET_LEVERAGE <= 3.50',     '3.10',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000007', 'ac000000-0000-0000-0000-000000000007', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 4.00',   'INTEREST_COVER >= 4.00',   '3.10',  'number',  'breached',  NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000008', 'ac000000-0000-0000-0000-000000000008', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'TANGIBLE_NET_WORTH >= 45000','TANGIBLE_NET_WORTH >= 45000','58000','number','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000009', 'ac000000-0000-0000-0000-000000000009', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.50',             'DSCR >= 1.50',             '1.95',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000010', 'ac000000-0000-0000-0000-000000000010', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 3.50',     'NET_LEVERAGE <= 3.50',     '2.75',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000011', 'ac000000-0000-0000-0000-000000000011', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 4.00',   'INTEREST_COVER >= 4.00',   '6.10',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000012', 'ac000000-0000-0000-0000-000000000012', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'RECV_COVER >= 1.25',       'RECV_COVER >= 1.25',       '1.60',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000013', 'ac000000-0000-0000-0000-000000000013', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.30',             'DSCR >= 1.30',             '1.55',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000014', 'ac000000-0000-0000-0000-000000000014', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 3.75',     'NET_LEVERAGE <= 3.75',     '3.20',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000015', 'ac000000-0000-0000-0000-000000000015', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 3.00',   'INTEREST_COVER >= 3.00',   '4.10',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000016', 'ac000000-0000-0000-0000-000000000016', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'CRUDE_OUTPUT >= 6.0',      'CRUDE_OUTPUT >= 6.0',      '7.2',   'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000017', 'ac000000-0000-0000-0000-000000000017', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.30',             'DSCR >= 1.30',             '1.42',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000018', 'ac000000-0000-0000-0000-000000000018', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 3.75',     'NET_LEVERAGE <= 3.75',     '4.10',  'number',  'breached',  NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000019', 'ac000000-0000-0000-0000-000000000019', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 3.00',   'INTEREST_COVER >= 3.00',   '3.60',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000020', 'ac000000-0000-0000-0000-000000000020', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'CRUDE_OUTPUT >= 6.0',      'CRUDE_OUTPUT >= 6.0',      '6.8',   'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000021', 'ac000000-0000-0000-0000-000000000021', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.40',             'DSCR >= 1.40',             '1.68',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000022', 'ac000000-0000-0000-0000-000000000022', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 4.00',     'NET_LEVERAGE <= 4.00',     '3.55',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000023', 'ac000000-0000-0000-0000-000000000023', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 3.50',   'INTEREST_COVER >= 3.50',   '4.40',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000024', 'ac000000-0000-0000-0000-000000000024', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'RECV_COVER >= 1.25',       'RECV_COVER >= 1.25',       '1.48',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000025', 'ac000000-0000-0000-0000-000000000025', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.40',             'DSCR >= 1.40',             '1.28',  'number',  'breached',  NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000026', 'ac000000-0000-0000-0000-000000000026', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 4.00',     'NET_LEVERAGE <= 4.00',     '3.70',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000027', 'ac000000-0000-0000-0000-000000000027', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 3.50',   'INTEREST_COVER >= 3.50',   '3.95',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000028', 'ac000000-0000-0000-0000-000000000028', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'RECV_COVER >= 1.25',       'RECV_COVER >= 1.25',       '1.35',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000029', 'ac000000-0000-0000-0000-000000000029', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.50',             'DSCR >= 1.50',             '1.90',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000030', 'ac000000-0000-0000-0000-000000000030', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 3.00',     'NET_LEVERAGE <= 3.00',     '2.40',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000031', 'ac000000-0000-0000-0000-000000000031', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 4.00',   'INTEREST_COVER >= 4.00',   '5.50',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000032', 'ac000000-0000-0000-0000-000000000032', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'TANGIBLE_NET_WORTH >= 45000','TANGIBLE_NET_WORTH >= 45000','71000','number','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000033', 'ac000000-0000-0000-0000-000000000033', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.50',             'DSCR >= 1.50',             '2.05',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000034', 'ac000000-0000-0000-0000-000000000034', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 3.00',     'NET_LEVERAGE <= 3.00',     '2.30',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000035', 'ac000000-0000-0000-0000-000000000035', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 4.00',   'INTEREST_COVER >= 4.00',   '5.80',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000036', 'ac000000-0000-0000-0000-000000000036', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'TANGIBLE_NET_WORTH >= 45000','TANGIBLE_NET_WORTH >= 45000','69000','number','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000037', 'ac000000-0000-0000-0000-000000000037', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.50',             'DSCR >= 1.50',             '1.78',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000038', 'ac000000-0000-0000-0000-000000000038', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 2.50',     'NET_LEVERAGE <= 2.50',     '1.95',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000039', 'ac000000-0000-0000-0000-000000000039', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 4.00',   'INTEREST_COVER >= 4.00',   '6.30',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000040', 'ac000000-0000-0000-0000-000000000040', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'EXPORT_LICENCE_VALID == true','EXPORT_LICENCE_VALID == true','true','boolean','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000041', 'ac000000-0000-0000-0000-000000000041', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.50',             'DSCR >= 1.50',             '1.82',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000042', 'ac000000-0000-0000-0000-000000000042', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 2.50',     'NET_LEVERAGE <= 2.50',     '2.10',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000043', 'ac000000-0000-0000-0000-000000000043', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 4.00',   'INTEREST_COVER >= 4.00',   '5.90',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000044', 'ac000000-0000-0000-0000-000000000044', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'EXPORT_LICENCE_VALID == true','EXPORT_LICENCE_VALID == true','true','boolean','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000045', 'ac000000-0000-0000-0000-000000000045', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 3.75',     'NET_LEVERAGE <= 3.75',     '3.40',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000046', 'ac000000-0000-0000-0000-000000000046', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.30',             'DSCR >= 1.30',             '1.52',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000047', 'ac000000-0000-0000-0000-000000000047', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 3.00',   'INTEREST_COVER >= 3.00',   '3.85',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000048', 'ac000000-0000-0000-0000-000000000048', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'CRUDE_OUTPUT >= 6.0',      'CRUDE_OUTPUT >= 6.0',      '6.5',   'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000049', 'ac000000-0000-0000-0000-000000000049', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 3.75',     'NET_LEVERAGE <= 3.75',     '3.60',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000050', 'ac000000-0000-0000-0000-000000000050', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.30',             'DSCR >= 1.30',             '1.48',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000051', 'ac000000-0000-0000-0000-000000000051', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 3.00',   'INTEREST_COVER >= 3.00',   '3.40',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000052', 'ac000000-0000-0000-0000-000000000052', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'CO2_INTENSITY <= 2.10',    'CO2_INTENSITY <= 2.10',    '2.24',  'number',  'breached',  NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000053', 'ac000000-0000-0000-0000-000000000053', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.50',             'DSCR >= 1.50',             '2.10',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000054', 'ac000000-0000-0000-0000-000000000054', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 2.00',     'NET_LEVERAGE <= 2.00',     '1.55',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000055', 'ac000000-0000-0000-0000-000000000055', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 5.00',   'INTEREST_COVER >= 5.00',   '7.20',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000056', 'ac000000-0000-0000-0000-000000000056', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'TANGIBLE_NET_WORTH >= 45000','TANGIBLE_NET_WORTH >= 45000','82000','number','compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000057', 'ac000000-0000-0000-0000-000000000057', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'DSCR >= 1.40',             'DSCR >= 1.40',             '1.66',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000058', 'ac000000-0000-0000-0000-000000000058', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'NET_LEVERAGE <= 2.50',     'NET_LEVERAGE <= 2.50',     '2.05',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000059', 'ac000000-0000-0000-0000-000000000059', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'INTEREST_COVER >= 4.00',   'INTEREST_COVER >= 4.00',   '5.10',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0'),
    ('a5000000-0000-0000-0000-000000000060', 'ac000000-0000-0000-0000-000000000060', '2026-03-31 02:00:00', '2026-04-01 02:05:00', '{"asOf":"2026-03-31"}', 'RECV_COVER >= 1.25',       'RECV_COVER >= 1.25',       '1.42',  'number',  'compliant', NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed', '2026-04-01 02:05:00','system','2026-04-01 02:05:00','system','0');
-- =====================================================================================
-- 10. cov_monitoring_schedule — one Quartz binding per applied covenant (quarterly)
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
-- 11. cov_covenant_recommendation — each applied covenant was an ACCEPTED recommendation
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
-- 12. cov_applied_covenant_audit — activation (status) trail for every applied covenant
-- =====================================================================================
INSERT INTO cov_applied_covenant_audit
    (id, applied_covenant_id, change_type, old_value, new_value, changed_date, changed_by)
SELECT REPLACE(ac.id, 'ac000000', '9a000000'),
       ac.id, 'status', NULL, 'active', '2025-04-01 10:00:00', 'seed'
FROM cov_applied_covenant ac;


-- =====================================================================================
-- 13. cov_expression_change_log — a couple of master expression edits
-- =====================================================================================
INSERT INTO cov_expression_change_log
    (id, covenant_id, old_expression, new_expression, changed_date, changed_by) VALUES
    ('ec000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001',
     'DSCR >= 1.40', 'DSCR >= 1.50', '2025-02-01 11:00:00', 'covenant-admin'),
    ('ec000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000005',
     'CO2_INTENSITY <= 2.20', 'CO2_INTENSITY <= 2.10', '2025-02-01 11:05:00', 'covenant-admin');


-- =====================================================================================
-- 14. cov_threshold_audit — provenance for the USER / ML threshold overrides
--     applied 7 (user), 13 (user), 18 (ml), 52 (ml). ml rows get a recommended + applied pair.
-- =====================================================================================
INSERT INTO cov_threshold_audit
    (id, cp_id, covenant_master_id, applied_covenant_id, source, event,
     old_expression, new_expression, confidence, rationale, changed_by, changed_date) VALUES
    ('7a000000-0000-0000-0000-000000000001', 'CP-1002', 'c0000000-0000-0000-0000-000000000003', 'ac000000-0000-0000-0000-000000000007', 'user', 'applied',     'INTEREST_COVER >= 3.50', 'INTEREST_COVER >= 4.00', NULL,   'RM tightened interest-cover floor at sanction.', 'rm.user',  '2025-04-01 10:00:00'),
    ('7a000000-0000-0000-0000-000000000002', 'CP-1004', 'c0000000-0000-0000-0000-000000000001', 'ac000000-0000-0000-0000-000000000013', 'user', 'applied',     'DSCR >= 1.50',           'DSCR >= 1.30',           NULL,   'Negotiated DSCR relief for ramp-up period.',     'rm.user',  '2025-04-01 10:00:00'),
    ('7a000000-0000-0000-0000-000000000003', 'CP-1005', 'c0000000-0000-0000-0000-000000000002', 'ac000000-0000-0000-0000-000000000018', 'ml',   'recommended', 'NET_LEVERAGE <= 3.50',   'NET_LEVERAGE <= 3.75',   0.8200, 'Peer-calibrated leverage ceiling.',              'ml-model', '2025-03-28 04:00:00'),
    ('7a000000-0000-0000-0000-000000000004', 'CP-1005', 'c0000000-0000-0000-0000-000000000002', 'ac000000-0000-0000-0000-000000000018', 'ml',   'applied',     'NET_LEVERAGE <= 3.50',   'NET_LEVERAGE <= 3.75',   0.8200, 'Accepted ML leverage recommendation.',           'rm.user',  '2025-04-01 10:00:00'),
    ('7a000000-0000-0000-0000-000000000005', 'CP-1013', 'c0000000-0000-0000-0000-000000000005', 'ac000000-0000-0000-0000-000000000052', 'ml',   'recommended', 'CO2_INTENSITY <= 2.20',  'CO2_INTENSITY <= 2.10',  0.7600, 'SLT trajectory tightened per decarb plan.',      'ml-model', '2025-03-28 04:00:00'),
    ('7a000000-0000-0000-0000-000000000006', 'CP-1013', 'c0000000-0000-0000-0000-000000000005', 'ac000000-0000-0000-0000-000000000052', 'ml',   'applied',     'CO2_INTENSITY <= 2.20',  'CO2_INTENSITY <= 2.10',  0.7600, 'Accepted ML SLT recommendation.',                'rm.user',  '2025-04-01 10:00:00');


-- =====================================================================================
-- 15. cov_notification_audit — one daily breach-run call covering the 4 breach CPs
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
-- 16. cov_covenant_disposition — RM/AI dispositions on the four breaches
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
-- 17. cov_covenant_status_audit — append-only trail for each disposition (breached -> new)
-- =====================================================================================
INSERT INTO cov_covenant_status_audit
    (id, cp_id, applied_covenant_id, assessment_id, old_status, new_status,
     old_extension_date, new_extension_date, source, rationale, changed_by, changed_date) VALUES
    ('5a000000-0000-0000-0000-000000000001', 'CP-1002', 'ac000000-0000-0000-0000-000000000007', 'a5000000-0000-0000-0000-000000000007', 'breached', 'waiver',    NULL, NULL,         'user', 'One-time waiver; interest cover expected to recover next quarter.', 'rm.user', '2026-04-02 09:00:00'),
    ('5a000000-0000-0000-0000-000000000002', 'CP-1005', 'ac000000-0000-0000-0000-000000000018', 'a5000000-0000-0000-0000-000000000018', 'breached', 'extension', NULL, '2026-09-30', 'user', 'Extension granted pending deleveraging plan.',                      'rm.user', '2026-04-02 09:10:00'),
    ('5a000000-0000-0000-0000-000000000003', 'CP-1007', 'ac000000-0000-0000-0000-000000000025', 'a5000000-0000-0000-0000-000000000025', 'breached', 'waiver',    NULL, NULL,         'user', 'Waived; DSCR shortfall driven by one-off capex timing.',            'rm.user', '2026-04-02 09:20:00'),
    ('5a000000-0000-0000-0000-000000000004', 'CP-1013', 'ac000000-0000-0000-0000-000000000052', 'a5000000-0000-0000-0000-000000000052', 'breached', 'extension', NULL, '2026-12-31', 'ai',   'AI-suggested extension aligned to green-steel transition roadmap.', 'rm.user', '2026-04-02 09:30:00');


-- =====================================================================================
-- 18. cov_covenant_attachment — evidence files on the breached covenant rows
-- =====================================================================================
INSERT INTO cov_covenant_attachment
    (id, cp_id, applied_covenant_id, file_id, file_name, uploaded_by, uploaded_at) VALUES
    ('a7000000-0000-0000-0000-000000000001', 'CP-1002', 'ac000000-0000-0000-0000-000000000007', 'file-cp1002-ic-01',  'CP-1002-interest-cover-note.pdf', 'rm.user', '2026-04-02 08:55:00'),
    ('a7000000-0000-0000-0000-000000000002', 'CP-1005', 'ac000000-0000-0000-0000-000000000018', 'file-cp1005-nl-01',  'CP-1005-deleveraging-plan.pdf',   'rm.user', '2026-04-02 09:05:00'),
    ('a7000000-0000-0000-0000-000000000003', 'CP-1007', 'ac000000-0000-0000-0000-000000000025', 'file-cp1007-dscr-01', 'CP-1007-capex-schedule.pdf',      'rm.user', '2026-04-02 09:15:00'),
    ('a7000000-0000-0000-0000-000000000004', 'CP-1013', 'ac000000-0000-0000-0000-000000000052', 'file-cp1013-co2-01',  'CP-1013-decarb-roadmap.pdf',      'rm.user', '2026-04-02 09:25:00');


-- =====================================================================================
-- 19. cov_compliance_certificate — one certificate per CP (CP-1001 .. CP-1015)
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
