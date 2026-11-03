-- DPS statement values are annual: a value older than ~13 months is stale (T9).
-- Kept apart from V17 because SQL Server compiles a whole script as one batch, so an UPDATE of a column
-- added in the same script fails with "Invalid column name".
UPDATE cov_measure SET max_age_days = 400 WHERE code LIKE 'DPS!_%' ESCAPE '!';
