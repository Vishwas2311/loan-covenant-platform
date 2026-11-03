-- =====================================================================================
-- V17 — Integration R1 (T9): staleness limit for point-in-time (LATEST / CURRENT) reads.
-- =====================================================================================
-- max_age_days (optional) caps how old the latest value may be relative to the as-of date. An older
-- value is treated as missing, so the assessment ends in ERROR ("Missing values for measures")
-- instead of silently testing last year's number. NULL keeps today's behaviour (no limit).
-- DPS statement measures get 400 days (an annual statement plus a filing grace period) in db/integration V19.
-- =====================================================================================
ALTER TABLE cov_measure ADD max_age_days INT
    CONSTRAINT ck_measure_max_age CHECK (max_age_days IS NULL OR max_age_days > 0);
