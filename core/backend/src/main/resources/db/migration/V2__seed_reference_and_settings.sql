-- =====================================================================================
-- V2 — production seed: eligibility reference catalogs + reporting-calendar policy.
-- =====================================================================================
-- These are the two seeds the application requires even without demo data:
--   * cov_reference_data  (eligibility catalogs — 45 rows)
--   * cov_reporting_setting (singleton org reporting-calendar policy — 1 row)
-- Copied verbatim from db/all_data.sql (sections 1 and 2).
-- =====================================================================================

-- =====================================================================================
-- 1. cov_reference_data — eligibility catalogs (from V18)
-- =====================================================================================
INSERT INTO cov_reference_data (id, category, code, label, sort_order, active) VALUES
    ('4f000000-0000-0000-0000-000000000001', 'SEGMENT', 'RETAIL',                 'Retail',                 1, 1),
    ('4f000000-0000-0000-0000-000000000002', 'SEGMENT', 'SME',                    'SME',                    2, 1),
    ('4f000000-0000-0000-0000-000000000003', 'SEGMENT', 'COMMERCIAL',             'Commercial',             3, 1),
    ('4f000000-0000-0000-0000-000000000004', 'SEGMENT', 'CORPORATE',              'Corporate',              4, 1),
    ('4f000000-0000-0000-0000-000000000005', 'SEGMENT', 'INFRASTRUCTURE',         'Infrastructure',         5, 1),
    ('4f000000-0000-0000-0000-000000000006', 'SEGMENT', 'CONGLOMERATE',           'Conglomerate',           6, 1),
    ('4f000000-0000-0000-0000-000000000007', 'SEGMENT', 'FINANCIAL_INSTITUTION',  'Financial Institution',  7, 1),
    ('4f000000-0000-0000-0000-000000000008', 'SEGMENT', 'GOVERNMENT',             'Government',             8, 1);

INSERT INTO cov_reference_data (id, category, code, label, sort_order, active) VALUES
    ('4f000000-0000-0000-0000-000000000009', 'INDUSTRY', 'INFRASTRUCTURE',     'Infrastructure',        1, 1),
    ('4f000000-0000-0000-0000-000000000010', 'INDUSTRY', 'METALS_MINING',      'Metals & Mining',       2, 1),
    ('4f000000-0000-0000-0000-000000000011', 'INDUSTRY', 'MANUFACTURING',      'Manufacturing',         3, 1),
    ('4f000000-0000-0000-0000-000000000012', 'INDUSTRY', 'TELECOM',            'Telecom',               4, 1),
    ('4f000000-0000-0000-0000-000000000013', 'INDUSTRY', 'POWER',              'Power & Utilities',     5, 1),
    ('4f000000-0000-0000-0000-000000000014', 'INDUSTRY', 'OIL_GAS',            'Oil & Gas',             6, 1),
    ('4f000000-0000-0000-0000-000000000015', 'INDUSTRY', 'REAL_ESTATE',        'Real Estate',           7, 1),
    ('4f000000-0000-0000-0000-000000000016', 'INDUSTRY', 'IT_SERVICES',        'IT & Services',         8, 1),
    ('4f000000-0000-0000-0000-000000000017', 'INDUSTRY', 'PHARMACEUTICALS',    'Pharmaceuticals',       9, 1),
    ('4f000000-0000-0000-0000-000000000018', 'INDUSTRY', 'AUTOMOTIVE',         'Automotive',           10, 1),
    ('4f000000-0000-0000-0000-000000000019', 'INDUSTRY', 'CHEMICALS',          'Chemicals',            11, 1),
    ('4f000000-0000-0000-0000-000000000020', 'INDUSTRY', 'TEXTILES',           'Textiles',             12, 1),
    ('4f000000-0000-0000-0000-000000000021', 'INDUSTRY', 'AGRICULTURE',        'Agriculture',          13, 1),
    ('4f000000-0000-0000-0000-000000000022', 'INDUSTRY', 'HEALTHCARE',         'Healthcare',           14, 1),
    ('4f000000-0000-0000-0000-000000000023', 'INDUSTRY', 'FINANCIAL_SERVICES', 'Financial Services',   15, 1),
    ('4f000000-0000-0000-0000-000000000024', 'INDUSTRY', 'RETAIL_TRADE',       'Retail & Trade',       16, 1);

INSERT INTO cov_reference_data (id, category, code, label, sort_order, active) VALUES
    ('4f000000-0000-0000-0000-000000000025', 'COUNTRY', 'IN', 'India',                 1, 1),
    ('4f000000-0000-0000-0000-000000000026', 'COUNTRY', 'AE', 'United Arab Emirates',  2, 1),
    ('4f000000-0000-0000-0000-000000000027', 'COUNTRY', 'SA', 'Saudi Arabia',          3, 1),
    ('4f000000-0000-0000-0000-000000000028', 'COUNTRY', 'US', 'United States',         8, 1),
    ('4f000000-0000-0000-0000-000000000029', 'COUNTRY', 'GB', 'United Kingdom',        9, 1),
    ('4f000000-0000-0000-0000-000000000030', 'COUNTRY', 'SG', 'Singapore',            10, 1);

INSERT INTO cov_reference_data (id, category, code, label, sort_order, active) VALUES
    ('4f000000-0000-0000-0000-000000000031', 'CREDIT_PROPOSAL_TYPE', 'TERM_LOAN',       'Term Loan',        1, 1),
    ('4f000000-0000-0000-0000-000000000032', 'CREDIT_PROPOSAL_TYPE', 'WORKING_CAPITAL', 'Working Capital',  2, 1),
    ('4f000000-0000-0000-0000-000000000033', 'CREDIT_PROPOSAL_TYPE', 'PROJECT_FINANCE', 'Project Finance',  3, 1),
    ('4f000000-0000-0000-0000-000000000034', 'CREDIT_PROPOSAL_TYPE', 'TRADE_FINANCE',   'Trade Finance',    4, 1),
    ('4f000000-0000-0000-0000-000000000035', 'CREDIT_PROPOSAL_TYPE', 'SYNDICATED_LOAN', 'Syndicated Loan',  7, 1);

INSERT INTO cov_reference_data (id, category, code, label, sort_order, active) VALUES
    ('4f000000-0000-0000-0000-000000000036', 'LEGAL_ENTITY_TYPE', 'PUBLIC_LTD',  'Public Limited',  1, 1),
    ('4f000000-0000-0000-0000-000000000037', 'LEGAL_ENTITY_TYPE', 'PRIVATE_LTD', 'Private Limited', 2, 1),
    ('4f000000-0000-0000-0000-000000000038', 'LEGAL_ENTITY_TYPE', 'SPV',         'Special Purpose Vehicle', 5, 1);

INSERT INTO cov_reference_data (id, category, code, label, sort_order, active) VALUES
    ('4f000000-0000-0000-0000-000000000039', 'FACILITY_TYPE', 'TERM_LOAN',        'Term Loan',        2, 1),
    ('4f000000-0000-0000-0000-000000000040', 'FACILITY_TYPE', 'BANK_GUARANTEE',   'Bank Guarantee',   3, 1),
    ('4f000000-0000-0000-0000-000000000041', 'FACILITY_TYPE', 'LETTER_OF_CREDIT', 'Letter of Credit', 4, 1);

INSERT INTO cov_reference_data (id, category, code, label, sort_order, active) VALUES
    ('4f000000-0000-0000-0000-000000000042', 'COLLATERAL_TYPE', 'PROPERTY',            'Property',          1, 1),
    ('4f000000-0000-0000-0000-000000000043', 'COLLATERAL_TYPE', 'PLANT_AND_MACHINERY', 'Plant & Machinery', 2, 1),
    ('4f000000-0000-0000-0000-000000000044', 'COLLATERAL_TYPE', 'RECEIVABLES',         'Receivables',       3, 1),
    ('4f000000-0000-0000-0000-000000000045', 'COLLATERAL_TYPE', 'UNSECURED',           'Unsecured',         9, 1);


-- =====================================================================================
-- 2. cov_reporting_setting — singleton org reporting-calendar policy (from V28)
-- =====================================================================================
INSERT INTO cov_reporting_setting
    (id, fiscal_year_start_month, periodic_includes_current_period,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('4e000000-0000-0000-0000-000000000001', 4, 0,
        '2025-01-01 00:00:00', 'seed', '2025-01-01 00:00:00', 'seed', '0');
