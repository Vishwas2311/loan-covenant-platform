-- =====================================================================================
-- V23 — Extension / Waiver test dataset (dev-only demo seed)
-- -------------------------------------------------------------------------------------
-- Purpose: give QA a ready-made set of CPs on which to exercise the breach dispositions
-- (US-MON-16: WAIVER + EXTENSION). A disposition can only be set on a covenant whose
-- LATEST assessment result is 'breached', so this seed stamps completed 'breached'
-- assessments up front and leaves them UN-disposed (the whole point is to create the
-- waiver/extension in the UI).
--
-- Shape (per the request):
--   * 10 new credit proposals ..................... CP-2001 .. CP-2010
--   * 12 library covenants mapped to each CP ....... masters c...001 .. c...012 (>= 10)
--   * 4 breached covenants on every CP ............. slots 01 (DSCR), 03 (INTEREST_COVER),
--                                                    09 (CURRENT_RATIO), 11 (DEBT_EQUITY)
--     -> 2 to WAIVER + 2 to EXTENSION on each CP, with 8 compliant rows as controls.
--
-- Non-destructive / additive: brand-new CP + applied-covenant + assessment IDs, so there
-- is NO collision with the existing V10 demo (CP-1001..CP-1015 / ac000000.. / a5000000..)
-- and NO need to DELETE from the persistent H2 file DB. The V10 demo stays intact; if a
-- truly "clean" set is ever wanted, delete WHERE cp_id LIKE 'CP-20%' and re-run — every
-- row this file writes is scoped to that prefix.
--
-- Portfolio ownership (so the RM-scoped monitoring grid actually shows them — the grid
-- filters by cov_credit_proposal_cache.rm_user_id / wrm_user_id, V11/V22):
--   * rm-anil  (Anil Kapoor)  / wrm-meera  (Meera Nair)  -> CP-2001 .. CP-2005
--   * rm-priya (Priya Sharma) / wrm-vikram (Vikram Rao)  -> CP-2006 .. CP-2010
--   (matches the DevUserSwitcher identities in the frontend; default login is rm-anil.)
--
-- Timing chosen so the daily breach sweep (BreachNotificationService) does NOT reassess
-- these rows and overwrite the seeded breaches: the covenants are quarterly and were last
-- assessed 2026-07-01, so their next due date is 2026-10-01 (future) -> not "due" -> the
-- sweep skips them. (CP-20xx have no cp_metrics_demo values, so a reassessment would
-- otherwise flip 'breached' -> error/NA and defeat the test.)
--
-- ID prefixes (all keyed by CP index KK=01..10 and covenant slot SS=01..12):
--   applied covenant .... ab0000KK-0000-0000-0000-0000000000SS
--   assessment .......... af0000KK-0000-0000-0000-0000000000SS
--   monitoring schedule . 5b0000KK-...   recommendation . 4e0000KK-...   audit . 9b0000KK-...
-- =====================================================================================


-- =====================================================================================
-- 0. Clean any pre-existing CP-20xx data first (FK-safe: children before parents), so the
--    seed owns the whole "CP-20%" namespace and is re-runnable. On a fresh DB every DELETE
--    is a harmless no-op; where leftover onboarding stubs exist (e.g. an empty CP-2001)
--    they are cleared so the INSERTs below cannot hit a duplicate-key. This is the
--    authorised "delete existing CP data for a clean dataset" step and is scoped strictly
--    to CP-20xx — the V10 demo (CP-1001..CP-1015) and all other data are untouched.
-- =====================================================================================
DELETE FROM cov_covenant_disposition    WHERE cp_id LIKE 'CP-20%';
DELETE FROM cov_covenant_status_audit   WHERE cp_id LIKE 'CP-20%';
DELETE FROM cov_covenant_attachment     WHERE cp_id LIKE 'CP-20%';
DELETE FROM cov_threshold_audit         WHERE cp_id LIKE 'CP-20%';
DELETE FROM cov_assessment              WHERE applied_covenant_id IN
    (SELECT id FROM cov_applied_covenant WHERE cp_id LIKE 'CP-20%');
DELETE FROM cov_monitoring_schedule     WHERE applied_covenant_id IN
    (SELECT id FROM cov_applied_covenant WHERE cp_id LIKE 'CP-20%');
DELETE FROM cov_applied_covenant_audit  WHERE applied_covenant_id IN
    (SELECT id FROM cov_applied_covenant WHERE cp_id LIKE 'CP-20%');
DELETE FROM cov_covenant_recommendation WHERE cp_id LIKE 'CP-20%';
DELETE FROM cov_compliance_certificate  WHERE cp_id LIKE 'CP-20%';
DELETE FROM cov_obligor_cp_map          WHERE cp_id LIKE 'CP-20%';
DELETE FROM cov_applied_covenant        WHERE cp_id LIKE 'CP-20%';
DELETE FROM cov_credit_proposal_cache   WHERE cp_id LIKE 'CP-20%';


-- =====================================================================================
-- 1. cov_credit_proposal_cache — one row per new CP, with RM/WRM ownership set
-- =====================================================================================
INSERT INTO cov_credit_proposal_cache
    (cp_id, business_segment, sector, attributes_json, fetched_at, rm_user_id, wrm_user_id) VALUES
    ('CP-2001', 'CORPORATE',     'METALS_MINING',  '{"borrower":"Vedanta Ltd","facility":"Aluminium Smelter Expansion"}',        '2026-07-05 06:00:00', 'rm-anil',  'wrm-meera'),
    ('CP-2002', 'CORPORATE',     'POWER',          '{"borrower":"NTPC Ltd","facility":"Solar Capacity Addition"}',               '2026-07-05 06:00:00', 'rm-anil',  'wrm-meera'),
    ('CP-2003', 'CONGLOMERATE',  'DIVERSIFIED',    '{"borrower":"Aditya Birla Group","facility":"Cement Capacity Expansion"}',   '2026-07-05 06:00:00', 'rm-anil',  'wrm-meera'),
    ('CP-2004', 'CORPORATE',     'PHARMA',         '{"borrower":"Sun Pharmaceutical Industries Ltd","facility":"USFDA Upgrade"}','2026-07-05 06:00:00', 'rm-anil',  'wrm-meera'),
    ('CP-2005', 'CORPORATE',     'IT_SERVICES',    '{"borrower":"Infosys Ltd","facility":"Global Delivery Centre"}',             '2026-07-05 06:00:00', 'rm-anil',  'wrm-meera'),
    ('CP-2006', 'CORPORATE',     'FMCG',           '{"borrower":"Hindustan Unilever Ltd","facility":"Distribution Financing"}',  '2026-07-05 06:00:00', 'rm-priya', 'wrm-vikram'),
    ('CP-2007', 'INFRASTRUCTURE','INFRASTRUCTURE', '{"borrower":"GMR Airports Ltd","facility":"Terminal Modernisation"}',        '2026-07-05 06:00:00', 'rm-priya', 'wrm-vikram'),
    ('CP-2008', 'CORPORATE',     'CEMENT',         '{"borrower":"UltraTech Cement Ltd","facility":"Grinding Unit Setup"}',       '2026-07-05 06:00:00', 'rm-priya', 'wrm-vikram'),
    ('CP-2009', 'CORPORATE',     'AUTOMOTIVE',     '{"borrower":"Maruti Suzuki India Ltd","facility":"New Assembly Line"}',      '2026-07-05 06:00:00', 'rm-priya', 'wrm-vikram'),
    ('CP-2010', 'CORPORATE',     'RENEWABLES',     '{"borrower":"ReNew Power Ltd","facility":"Wind-Solar Hybrid Park"}',         '2026-07-05 06:00:00', 'rm-priya', 'wrm-vikram');


-- =====================================================================================
-- 2. cov_applied_covenant — 12 library covenants per CP (120 rows)
--    Built by cross-joining the 10 CPs with the 12 covenant slots. threshold_source
--    'master' throughout; slot 08 (export licence) is a precedent, the rest subsequent.
-- =====================================================================================
INSERT INTO cov_applied_covenant
    (id, cp_id, covenant_master_id, frequency, cron_expression, start_date, end_date, status,
     snapshot_json, condition_expression, condition_type, classification, threshold_source,
     created_date, created_by, modified_date, modified_by, version)
SELECT CONCAT('ab0000', cp.k, '-0000-0000-0000-0000000000', s.s),
       cp.cp_id, s.master_id, 'quarterly', NULL, '2025-07-01', NULL, 'active',
       '{"seed":true}', s.expr, s.ctype, 'external', 'master',
       '2025-07-01 10:00:00', 'seed', '2026-07-01 02:05:00', 'system', '0'
FROM (VALUES
        ('01', 'CP-2001'), ('02', 'CP-2002'), ('03', 'CP-2003'), ('04', 'CP-2004'), ('05', 'CP-2005'),
        ('06', 'CP-2006'), ('07', 'CP-2007'), ('08', 'CP-2008'), ('09', 'CP-2009'), ('10', 'CP-2010')
     ) AS cp (k, cp_id)
CROSS JOIN (VALUES
        ('01', 'c0000000-0000-0000-0000-000000000001', 'DSCR >= 1.50',                'subsequent'),
        ('02', 'c0000000-0000-0000-0000-000000000002', 'NET_LEVERAGE <= 3.50',        'subsequent'),
        ('03', 'c0000000-0000-0000-0000-000000000003', 'INTEREST_COVER >= 4.00',      'subsequent'),
        ('04', 'c0000000-0000-0000-0000-000000000004', 'TANGIBLE_NET_WORTH >= 45000', 'subsequent'),
        ('05', 'c0000000-0000-0000-0000-000000000005', 'CO2_INTENSITY <= 2.10',       'subsequent'),
        ('06', 'c0000000-0000-0000-0000-000000000006', 'CRUDE_OUTPUT >= 6.0',         'subsequent'),
        ('07', 'c0000000-0000-0000-0000-000000000007', 'RECV_COVER >= 1.25',          'subsequent'),
        ('08', 'c0000000-0000-0000-0000-000000000008', 'EXPORT_LICENCE_VALID == true','precedent'),
        ('09', 'c0000000-0000-0000-0000-000000000009', 'CURRENT_RATIO >= 1.20',       'subsequent'),
        ('10', 'c0000000-0000-0000-0000-000000000010', 'QUICK_RATIO >= 1.00',         'subsequent'),
        ('11', 'c0000000-0000-0000-0000-000000000011', 'DEBT_EQUITY <= 2.00',         'subsequent'),
        ('12', 'c0000000-0000-0000-0000-000000000012', 'DEBT_EBITDA <= 4.00',         'subsequent')
     ) AS s (s, master_id, expr, ctype);


-- =====================================================================================
-- 3. cov_assessment — one completed run per applied covenant (120 rows)
--    Slots 01/03/09/11 -> 'breached' (4 per CP); everything else 'compliant'. Assessed
--    2026-07-01 so the daily sweep treats them as up-to-date (next due 2026-10-01).
-- =====================================================================================
INSERT INTO cov_assessment
    (id, applied_covenant_id, scheduled_at, executed_at, data_snapshot_json,
     evaluated_expression, condition_expression, computed_value, computed_value_type,
     result, error_message, assessment_note, workflow_instance_id, workflow_error,
     triggered_by, triggered_by_user, status,
     created_date, created_by, modified_date, modified_by, version)
SELECT CONCAT('af0000', cp.k, '-0000-0000-0000-0000000000', s.s),
       CONCAT('ab0000', cp.k, '-0000-0000-0000-0000000000', s.s),
       '2026-07-01 02:00:00', '2026-07-01 02:05:00', '{"asOf":"2026-06-30"}',
       s.expr, s.expr, s.cval, s.vtype, s.result,
       NULL, NULL, NULL, NULL, 'scheduled', NULL, 'completed',
       '2026-07-01 02:05:00', 'system', '2026-07-01 02:05:00', 'system', '0'
FROM (VALUES
        ('01', 'CP-2001'), ('02', 'CP-2002'), ('03', 'CP-2003'), ('04', 'CP-2004'), ('05', 'CP-2005'),
        ('06', 'CP-2006'), ('07', 'CP-2007'), ('08', 'CP-2008'), ('09', 'CP-2009'), ('10', 'CP-2010')
     ) AS cp (k, cp_id)
CROSS JOIN (VALUES
        ('01', 'DSCR >= 1.50',                '1.15',  'number',  'breached'),
        ('02', 'NET_LEVERAGE <= 3.50',        '2.80',  'number',  'compliant'),
        ('03', 'INTEREST_COVER >= 4.00',      '3.20',  'number',  'breached'),
        ('04', 'TANGIBLE_NET_WORTH >= 45000', '61000', 'number',  'compliant'),
        ('05', 'CO2_INTENSITY <= 2.10',       '1.85',  'number',  'compliant'),
        ('06', 'CRUDE_OUTPUT >= 6.0',         '7.10',  'number',  'compliant'),
        ('07', 'RECV_COVER >= 1.25',          '1.55',  'number',  'compliant'),
        ('08', 'EXPORT_LICENCE_VALID == true','true',  'boolean', 'compliant'),
        ('09', 'CURRENT_RATIO >= 1.20',       '1.05',  'number',  'breached'),
        ('10', 'QUICK_RATIO >= 1.00',         '1.30',  'number',  'compliant'),
        ('11', 'DEBT_EQUITY <= 2.00',         '2.55',  'number',  'breached'),
        ('12', 'DEBT_EBITDA <= 4.00',         '3.40',  'number',  'compliant')
     ) AS s (s, expr, cval, vtype, result);


-- =====================================================================================
-- 4. cov_monitoring_schedule — one quartz binding per applied covenant (scoped to CP-20%)
--    next_run_at in the future (2026-10-01) keeps the row consistent with the timing above.
-- =====================================================================================
INSERT INTO cov_monitoring_schedule
    (id, applied_covenant_id, quartz_job_key, cron_expression, next_run_at, last_run_at, status,
     created_date, created_by, modified_date, modified_by, version)
SELECT REPLACE(ac.id, 'ab0000', '5b0000'),
       ac.id,
       CONCAT('assessment-', ac.id),
       '0 0 2 1 1,4,7,10 ?',
       '2026-10-01 02:00:00',
       '2026-07-01 02:05:00',
       'scheduled',
       '2025-07-01 10:00:00', 'seed', '2026-07-01 02:05:00', 'system', '0'
FROM cov_applied_covenant ac
WHERE ac.cp_id LIKE 'CP-20%';


-- =====================================================================================
-- 5. cov_covenant_recommendation — each applied covenant was an ACCEPTED recommendation
-- =====================================================================================
INSERT INTO cov_covenant_recommendation
    (id, cp_id, covenant_master_id, match_reason, status, created_date, modified_date)
SELECT REPLACE(ac.id, 'ab0000', '4e0000'),
       ac.cp_id,
       ac.covenant_master_id,
       'Matched on business segment / industry eligibility.',
       'accepted',
       '2025-06-25 09:00:00',
       '2025-07-01 10:00:00'
FROM cov_applied_covenant ac
WHERE ac.cp_id LIKE 'CP-20%';


-- =====================================================================================
-- 6. cov_applied_covenant_audit — activation (status) trail for every applied covenant
-- =====================================================================================
INSERT INTO cov_applied_covenant_audit
    (id, applied_covenant_id, change_type, old_value, new_value, changed_date, changed_by)
SELECT REPLACE(ac.id, 'ab0000', '9b0000'),
       ac.id, 'status', NULL, 'active', '2025-07-01 10:00:00', 'seed'
FROM cov_applied_covenant ac
WHERE ac.cp_id LIKE 'CP-20%';


-- =====================================================================================
-- End of extension/waiver demo data.
--   Result: 10 CPs x 12 covenants = 120 applied covenants, 120 assessments, of which
--   40 are 'breached' (4 per CP) and awaiting a WAIVER / EXTENSION disposition.
--   Log in as rm-anil (CP-2001..2005) or rm-priya (CP-2006..2010) to disposition them.
-- =====================================================================================
