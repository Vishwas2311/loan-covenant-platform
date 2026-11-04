-- =====================================================================================
-- V30 - each covenant's latest assessment in one index seek.
--   The portfolio summary and CP views ask for the newest assessment of many covenants at once
--   (max(scheduled_at) per applied covenant). With only single-column indexes SQL Server scanned
--   the covenant's rows for every lookup (load test: 250-315 ms per batch at 20 users).
-- MSSQL-compatible and H2 MODE=MSSQLServer compatible.
-- =====================================================================================

CREATE INDEX ix_assessment_applied_scheduled ON cov_assessment(applied_covenant_id, scheduled_at);
