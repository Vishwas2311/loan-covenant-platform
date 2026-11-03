-- =====================================================================================
-- US-MON-23 : CP renewal lineage on the CP cache (parent link)
-- -------------------------------------------------------------------------------------
-- An obligor is renewed/refinanced over time, producing a chain of linked Credit
-- Proposals where each CP points to its immediate predecessor. The link is resolved from
-- the external CP service's parentCpId and denormalized here so the "cycle history"
-- viewer walks the chain by a plain lookup instead of a per-hop remote call.
--
--   * parent_cp_id : cp_id of this CP's immediate parent (nullable; a root CP has none)
--
-- Indexed for the "children of :cp" direction. Mirrors V5/V11's ADD-column style so the
-- DDL is MSSQL + H2 (MODE=MSSQLServer) compatible.
-- =====================================================================================
ALTER TABLE cov_credit_proposal_cache ADD parent_cp_id VARCHAR(64);
CREATE INDEX ix_cp_cache_parent ON cov_credit_proposal_cache(parent_cp_id);
