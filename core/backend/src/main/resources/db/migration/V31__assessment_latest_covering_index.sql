-- =====================================================================================
-- V31 - latest assessment per covenant, answered from the index alone.
--   findLatestOutcomes ranks each covenant's assessments by (scheduled_at desc, id desc) and reads
--   result. V30's index lacks id and result, so SQL Server sorted and looked up every row: with
--   60-CP portfolios it was the top database cost (7,821 calls at 269 ms of CPU each in a 10-minute
--   load test). With this index the same call for 240 covenants takes 8-12 ms.
--   result is a key column rather than INCLUDE so the script also runs on H2 (MODE=MSSQLServer).
-- =====================================================================================

CREATE INDEX ix_assessment_latest ON cov_assessment(applied_covenant_id, scheduled_at DESC, id DESC, result);
