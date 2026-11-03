-- =====================================================================================
-- US-MON-09 : configurable monitoring-schedule windows on the org settings singleton
-- -------------------------------------------------------------------------------------
-- The portfolio Monitoring Schedule groups events into filter buckets whose horizons were
-- previously hardcoded (7 / 30 / 14 days). These three windows are added to the existing
-- singleton cov_reporting_setting so an admin can tune how the filters classify events:
--
--   * due_soon_days        : a started covenant whose next due date is within N days is
--                            DUE_SOON ("Due soon" / "Due this week" bucket). Default 7.
--   * upcoming_window_days : the forward look-ahead of the schedule — how far out future
--                            events are listed ("Upcoming" bucket / default query window).
--                            Default 30.
--   * starting_soon_days   : a not-yet-started covenant whose start date is within N days
--                            is shown in "Starting soon". Default 14.
--
-- Existing (singleton) row backfills to the historical defaults, so behaviour is unchanged
-- until an admin edits the values. Each window is constrained to 1..365 days.
-- =====================================================================================
-- CHECKs are declared on the columns: SQL Server compiles the whole script as one batch, so a separate
-- ALTER referencing a column added earlier in the same script fails with "Invalid column name".
ALTER TABLE cov_reporting_setting ADD due_soon_days INT DEFAULT 7 NOT NULL
    CONSTRAINT ck_reporting_due_soon_days CHECK (due_soon_days BETWEEN 1 AND 365);
ALTER TABLE cov_reporting_setting ADD upcoming_window_days INT DEFAULT 30 NOT NULL
    CONSTRAINT ck_reporting_upcoming_days CHECK (upcoming_window_days BETWEEN 1 AND 365);
ALTER TABLE cov_reporting_setting ADD starting_soon_days INT DEFAULT 14 NOT NULL
    CONSTRAINT ck_reporting_starting_soon_days CHECK (starting_soon_days BETWEEN 1 AND 365);
