-- =====================================================================================
-- V18 — Integration R1 (T7, FR-23): reporting currency and dated FX rates.
-- =====================================================================================
-- Amounts keep their own currency; screens and reports convert to the organisation's reporting
-- currency with the latest rate on or before the amount's date. A rate is stored once per
-- (from, to, date); the inverse is derived when only the opposite direction is on file.
-- =====================================================================================
ALTER TABLE cov_reporting_setting ADD reporting_currency VARCHAR(3) DEFAULT 'INR' NOT NULL;

CREATE TABLE cov_fx_rate (
    id             VARCHAR(36)    NOT NULL,
    from_currency  VARCHAR(3)     NOT NULL,
    to_currency    VARCHAR(3)     NOT NULL,
    rate_date      DATE           NOT NULL,
    rate           DECIMAL(19,8)  NOT NULL,
    source         VARCHAR(64),
    created_date   DATETIME       NOT NULL,
    created_by     VARCHAR(50)    NOT NULL,
    modified_date  DATETIME       NOT NULL,
    modified_by    VARCHAR(50)    NOT NULL,
    version        VARCHAR(36)    NOT NULL,
    CONSTRAINT pk_fx_rate PRIMARY KEY (id),
    CONSTRAINT uk_fx_rate UNIQUE (from_currency, to_currency, rate_date),
    CONSTRAINT ck_fx_rate_positive CHECK (rate > 0),
    CONSTRAINT ck_fx_rate_pair CHECK (from_currency <> to_currency)
);
CREATE INDEX ix_fx_rate_lookup ON cov_fx_rate(from_currency, to_currency, rate_date);
