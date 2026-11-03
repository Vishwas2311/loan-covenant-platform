-- =====================================================================================
-- US-MON-09 / US-MON-15 : portfolio ownership on the CP cache (RM / WRM visibility)
-- -------------------------------------------------------------------------------------
-- Monitoring views are scoped to the logged-in Relationship Manager (RM) or Wholesale
-- Relationship Manager (WRM): a user sees only the CPs they are mapped to (as RM OR WRM).
-- Ownership is resolved from the external CP source (rm/wrm) and denormalized here so the
-- monitoring reads filter by a plain SQL predicate instead of a per-CP remote lookup.
--
--   * rm_user_id  : userId of the CP's Relationship Manager (nullable until first refresh)
--   * wrm_user_id : userId of the CP's Wholesale Relationship Manager (nullable)
--
-- Indexed on each column for the "CPs owned by :user" lookup (rm OR wrm). Mirrors V5's
-- ADD-column style so the DDL is MSSQL + H2 (MODE=MSSQLServer) compatible.
-- =====================================================================================
ALTER TABLE cov_credit_proposal_cache ADD rm_user_id VARCHAR(64);
ALTER TABLE cov_credit_proposal_cache ADD wrm_user_id VARCHAR(64);
CREATE INDEX ix_cp_cache_rm ON cov_credit_proposal_cache(rm_user_id);
CREATE INDEX ix_cp_cache_wrm ON cov_credit_proposal_cache(wrm_user_id);
