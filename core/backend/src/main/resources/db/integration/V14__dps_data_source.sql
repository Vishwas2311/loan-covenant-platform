-- =====================================================================================
-- V14 — Integration R1 (T10): the Document Processing Service as a Core data source.
-- =====================================================================================
-- DPS serves statement line items through Core's REST data-source contract (C2, POST /dps/v1/values).
-- Environment-neutral: no baseUrl, so Core uses covenant.clients.dps.base-url, and "auth":"dps" makes
-- the fetch carry the DPS service credentials. DPS sends C5 data-events with dataSourceId
-- d5000000-0000-0000-0000-000000000d95 (set DPS_CORE_DATA_SOURCE_ID to it).
--
-- Measures use a DPS_ prefix so they never clash with bank-fed measures such as DSCR (04 §6.2).
-- Two library covenants the agreements contain but the library lacked are added (04 §6.1).
-- =====================================================================================

INSERT INTO cov_data_source
    (id, name, type, description, connection_config_json, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('d5000000-0000-0000-0000-000000000d95', 'Document Processing Service (statements)', 'rest',
        'Line items read from uploaded financial statements (Flow B). Values are versioned per period; a re-upload supersedes the old value and DPS notifies Core with a data-event.',
        '{"valuesPath":"/dps/v1/values","auth":"dps"}',
        1, '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0');

INSERT INTO cov_data_source_table
    (id, data_source_id, name, schema_name, table_name, entity_key_column, as_of_column,
     table_layout, code_column, value_column, active,
     created_date, created_by, modified_date, modified_by, version, entity_key_level) VALUES
    ('d7000000-0000-0000-0000-000000000d95', 'd5000000-0000-0000-0000-000000000d95',
        'DPS statement values (CP)', NULL, 'dps_statement_value', 'cpId', 'asOfDate',
        'columnar', NULL, NULL, 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0', 'cp_id');

INSERT INTO cov_measure
    (id, code, name, description, data_source_table_id, source_column, source_code,
     default_aggregation, data_type, unit, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('3e000000-0000-0000-0000-000000d95001', 'DPS_REVENUE', 'Revenue (DPS)',
        'Revenue read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'REVENUE', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95002', 'DPS_EBITDA', 'EBITDA (DPS)',
        'EBITDA read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'EBITDA', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95003', 'DPS_EBIT', 'EBIT (DPS)',
        'EBIT read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'EBIT', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95004', 'DPS_DEPRECIATION', 'Depreciation and amortisation (DPS)',
        'Depreciation and amortisation read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'DEPRECIATION', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95005', 'DPS_INTEREST_PAID', 'Interest paid (DPS)',
        'Interest paid read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'INTEREST_PAID', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95006', 'DPS_PRINCIPAL_PAID', 'Principal repaid (DPS)',
        'Principal repaid read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'PRINCIPAL_PAID', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95007', 'DPS_EMI_AMOUNT', 'EMI amount (DPS)',
        'EMI amount read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'EMI_AMOUNT', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95008', 'DPS_PAT', 'Profit after tax (DPS)',
        'Profit after tax read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'PAT', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95009', 'DPS_TOTAL_DEBT', 'Total debt (DPS)',
        'Total debt read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'TOTAL_DEBT', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95010', 'DPS_ST_DEBT', 'Short-term debt (DPS)',
        'Short-term debt read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'ST_DEBT', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95011', 'DPS_LT_DEBT', 'Long-term debt (DPS)',
        'Long-term debt read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'LT_DEBT', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95012', 'DPS_NET_DEBT', 'Net debt (DPS)',
        'Net debt read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'NET_DEBT', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95013', 'DPS_CASH', 'Cash and equivalents (DPS)',
        'Cash and equivalents read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'CASH', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95014', 'DPS_NET_WORTH', 'Net worth (DPS)',
        'Net worth read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'NET_WORTH', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95015', 'DPS_TANGIBLE_NET_WORTH', 'Tangible net worth (DPS)',
        'Tangible net worth read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'TANGIBLE_NET_WORTH', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95016', 'DPS_CURRENT_ASSETS', 'Current assets (DPS)',
        'Current assets read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'CURRENT_ASSETS', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95017', 'DPS_CURRENT_LIABILITIES', 'Current liabilities (DPS)',
        'Current liabilities read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'CURRENT_LIABILITIES', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95018', 'DPS_INVENTORY', 'Inventory (DPS)',
        'Inventory read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'INVENTORY', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95019', 'DPS_RECEIVABLES', 'Receivables (DPS)',
        'Receivables read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'RECEIVABLES', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('3e000000-0000-0000-0000-000000d95020', 'DPS_CAPEX', 'Capital expenditure (DPS)',
        'Capital expenditure read from uploaded financial statements by the Document Processing Service, INR crore.',
        'd7000000-0000-0000-0000-000000000d95', 'CAPEX', NULL, 'latest', 'number', 'INR_CR', 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0');

INSERT INTO cov_covenant_master
    (id, name, category, description, business_segments, condition_expression, referenced_measures,
     islamic_banking, industries, countries_of_risk, countries_of_incorporation,
     credit_proposal_types, legal_entity_types, facility_types, collateral_types,
     assessment_type, default_frequency, default_cron, is_custom, owner_cp_id, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('c0000000-0000-0000-0000-000000000d95', 'Debt to Net Worth Ceiling', 'financial',
        'Total debt to net worth must not exceed the agreed ceiling (values from uploaded statements).',
        '["ALL"]', 'DPS_TOTAL_DEBT / DPS_NET_WORTH <= 2.50', '["DPS_TOTAL_DEBT","DPS_NET_WORTH"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0'),
    ('c0000000-0000-0000-0000-000000000d96', 'EBITDA to EMI Floor', 'financial',
        'EBITDA must cover the instalment (EMI) by at least the agreed multiple (values from uploaded statements).',
        '["ALL"]', 'DPS_EBITDA / DPS_EMI_AMOUNT >= 1.20', '["DPS_EBITDA","DPS_EMI_AMOUNT"]',
        0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        'auto', 'quarterly', NULL, 0, NULL, 1,
        '2026-11-02 00:00:00','flyway','2026-11-02 00:00:00','flyway','0');
