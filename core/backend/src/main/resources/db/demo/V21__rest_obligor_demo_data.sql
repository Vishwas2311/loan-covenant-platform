-- =====================================================================================
-- V21 (DEV only) — REST obligor-grain financials demo (US-DS-11 / US-DS-12 end-to-end).
-- =====================================================================================
-- Loaded only under the `dev` profile (classpath:db/demo), AFTER V20. Purely ADDITIVE — it
-- introduces a BRAND-NEW obligor group (Adani = OBL-ADANI) and two BRAND-NEW credit proposals
-- (CP-2001, CP-2002) that do not collide with any V10/V20 row, so existing demos are unaffected.
--
-- Scenario it wires up (README §9 "Example 7"):
--   An EXTERNAL REST data API publishes GROUP/OBLIGOR-grain financial ratios for MANY dates. Some
--   years the group files only an annual (fiscal-year-end 03-31) number; other years it also files
--   quarterly (06-30/09-30/12-31). A windowed covenant (e.g. AVG(GRP_DSCR,'3y',PERIODIC) >= 1.30)
--   therefore drives a multi-year SERIES fetch (US-DS-12/WS3: POST /api/data/values with a `from`),
--   lands the whole history in cov_staging_value on the first assessment, and fans the one obligor's
--   data out to BOTH of its CPs (US-DS-11: entity_key_level = 'obligor_id').
--
-- The ACTUAL data is served by the dev WireMock stub on port 9311:
--   • wiremock/mappings/demo-obligor-values-adani.json   → POST /api/data/values (matches cpId=OBL-ADANI; returns the whole series)
--   • wiremock/mappings/demo-cp-2001.json / demo-cp-2002 → GET  /api/credit-proposals/{cpId} (carry obligorId)
-- So there is no external service to run: register (seeded below) → assess → ingest → evaluate.
-- =====================================================================================


-- =====================================================================================
-- (A) Two brand-new CPs under one brand-new obligor group (Adani).
-- -------------------------------------------------------------------------------------
-- CP cache rows (no audit columns on this table). obligor_id is the denormalized forward copy;
-- the source of truth is cov_obligor_cp_map below. A live GET /api/v1/credit-proposals/{cpId}
-- also (re)captures the same mapping from the WireMock payload's obligorId.
-- =====================================================================================
INSERT INTO cov_credit_proposal_cache (cp_id, business_segment, sector, obligor_id, attributes_json, fetched_at) VALUES
    ('CP-2001', 'INFRASTRUCTURE', 'PORTS',      'OBL-ADANI', '{"borrower":"Adani Group","facility":"Mundra Port Capacity Expansion"}', '2026-04-09 06:00:00'),
    ('CP-2002', 'POWER',          'RENEWABLES', 'OBL-ADANI', '{"borrower":"Adani Group","facility":"Solar Park Working Capital"}',     '2026-04-09 06:00:00');


-- =====================================================================================
-- (B) Obligor -> CP mapping (source of truth). One obligor -> many CPs (UNIQUE on cp_id).
-- -------------------------------------------------------------------------------------
-- Seeded so the fan-out is testable without first calling the CP lookup. ids continue V20's
-- 0b1… prefix (…0006/…0007) so there is no collision with V20 (…0001..0005).
-- =====================================================================================
INSERT INTO cov_obligor_cp_map (id, obligor_id, cp_id, created_date, created_by, modified_date, modified_by, version) VALUES
    ('0b100000-0000-0000-0000-000000000006', 'OBL-ADANI', 'CP-2001', '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0'),
    ('0b100000-0000-0000-0000-000000000007', 'OBL-ADANI', 'CP-2002', '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0');


-- =====================================================================================
-- (C) Register the external REST source as cov_ metadata (the "onboarding" the UI/REST does).
-- -------------------------------------------------------------------------------------
-- type = 'rest'. WS4 per-source routing: connection_config_json.baseUrl is now USED — the fetch is
-- issued against baseUrl + valuesPath (default /api/data/values), bypassing the global data-api base
-- URL. In dev this points at the WireMock server (http://localhost:9311).
-- Distinct id prefixes (…0200) so there is no collision with V10 (…0001..0003) or V20 (…0100).
-- =====================================================================================
INSERT INTO cov_data_source
    (id, name, type, description, connection_config_json, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('d5000000-0000-0000-0000-000000000200', 'Demo Group Financials API (Obligor, REST)', 'rest',
        'External REST feed of group/obligor-grain financial ratios published for many dates (annual + quarterly). Windowed covenants pull a multi-year series via POST /api/data/values (WS3, single as-of-aware endpoint); in dev the WireMock stub answers for cpId=OBL-ADANI.',
        '{"baseUrl":"http://localhost:9311"}',
        1, '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0');

-- Obligor-keyed REST table. For REST the physical table_name / entity_key_column / as_of_column are
-- NOMINAL (no JDBC runs); the JSON keys returned ARE the measures' source_column. entity_key_level =
-- 'obligor_id' makes the resolver bind the CP's obligor as the fetch key and fan out to all its CPs.
INSERT INTO cov_data_source_table
    (id, data_source_id, name, schema_name, table_name, entity_key_column, as_of_column,
     table_layout, code_column, value_column, active,
     created_date, created_by, modified_date, modified_by, version, entity_key_level) VALUES
    ('d7000000-0000-0000-0000-000000000200', 'd5000000-0000-0000-0000-000000000200',
        'Group Financials API (Obligor, REST)', NULL, 'obligor_financials_api', 'obligorId', 'asOfDate',
        'columnar', NULL, NULL, 1,
        '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0', 'obligor_id');

-- Three obligor-level measures (columnar → source_column = the JSON key in the /series payload's
-- "values" map). Codes are what covenant formulas reference (e.g. AVG(GRP_DSCR,'3y',PERIODIC)).
INSERT INTO cov_measure
    (id, code, name, description, data_source_table_id, source_column, source_code,
     default_aggregation, data_type, unit, active,
     created_date, created_by, modified_date, modified_by, version) VALUES
    ('3e000000-0000-0000-0000-000000000200', 'GRP_DSCR', 'Group DSCR (Obligor, REST)',
        'Obligor-level debt service coverage ratio from the external REST feed, shared across the obligor''s CPs.',
        'd7000000-0000-0000-0000-000000000200', 'group_dscr', NULL,
        'latest', 'number', 'x', 1,
        '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0'),
    ('3e000000-0000-0000-0000-000000000201', 'GRP_NET_LEVERAGE', 'Group Net Leverage (Obligor, REST)',
        'Obligor-level net debt / EBITDA from the external REST feed.',
        'd7000000-0000-0000-0000-000000000200', 'group_net_leverage', NULL,
        'latest', 'number', 'x', 1,
        '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0'),
    ('3e000000-0000-0000-0000-000000000202', 'GRP_INTEREST_COVER', 'Group Interest Cover (Obligor, REST)',
        'Obligor-level interest coverage (EBIT / interest) from the external REST feed.',
        'd7000000-0000-0000-0000-000000000200', 'group_interest_cover', NULL,
        'latest', 'number', 'x', 1,
        '2026-04-09 06:00:00', 'seed', '2026-04-09 06:00:00', 'seed', '0');


-- =====================================================================================
-- End of V21 REST obligor-grain financials demo.
-- =====================================================================================
