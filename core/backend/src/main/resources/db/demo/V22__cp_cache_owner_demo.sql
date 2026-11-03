-- =====================================================================================
-- US-MON-09 / US-MON-15 : demo portfolio ownership (RM / WRM) for the seeded CPs
-- -------------------------------------------------------------------------------------
-- Dev-only seed so the monitoring portfolio scope is demoable end-to-end. Assigns the 15
-- demo CPs to two RM/WRM pairs (matching the DevUserSwitcher identities in the frontend):
--
--   * rm-anil  (Anil Kapoor)  / wrm-meera  (Meera Nair)   -> CP-1001..CP-1007
--   * rm-priya (Priya Sharma) / wrm-vikram (Vikram Rao)   -> CP-1008..CP-1015
--
-- Runs AFTER the demo cache seed (V10) and the prod column add (V11 — dev is out-of-order,
-- so V11's rm_user_id/wrm_user_id columns are present before this UPDATE). The null-safe
-- CachedCovenantAttributesService.save() preserves these owners across attribute refreshes
-- even when the demo CP stub omits rm/wrm, so the demo stays stable.
-- =====================================================================================
UPDATE cov_credit_proposal_cache SET rm_user_id = 'rm-anil', wrm_user_id = 'wrm-meera'
    WHERE cp_id IN ('CP-1001', 'CP-1002', 'CP-1003', 'CP-1004', 'CP-1005', 'CP-1006', 'CP-1007');

UPDATE cov_credit_proposal_cache SET rm_user_id = 'rm-priya', wrm_user_id = 'wrm-vikram'
    WHERE cp_id IN ('CP-1008', 'CP-1009', 'CP-1010', 'CP-1011', 'CP-1012', 'CP-1013', 'CP-1014', 'CP-1015');
