package com.crisil.covenant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * US-DS-13: externalised knobs for freshness-gated ingestion. Kept out of code so operations can tune
 * the gate without a redeploy.
 * <ul>
 *   <li>{@code ttlHours} — max-staleness floor for sources that cannot report a change timestamp: once
 *       an entity has been ingested, the gate skips it (on the freshness axis) until the TTL elapses,
 *       substituting for a real CDC signal.</li>
 *   <li>{@code lookaheadDays} — how far ahead the due-driven pre-stage job looks for imminent covenant
 *       due dates.</li>
 *   <li>{@code preStageCron} — Quartz cron for the pre-stage sweep (separate failure domain from the
 *       breach run).</li>
 *   <li>{@code mssqlPool} — HikariCP sizing for the per-source MSSQL connection pools kept by
 *       {@code JdbcDataSourceRegistry} (replaces the old per-ingest {@code DriverManagerDataSource}).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "covenant.ingestion")
public class IngestionProperties {

    private long ttlHours = 24;
    private int lookaheadDays = 3;
    private String preStageCron = "0 30 5 * * ?";
    private final MssqlPool mssqlPool = new MssqlPool();

    public long getTtlHours() {
        return ttlHours;
    }

    public void setTtlHours(long ttlHours) {
        this.ttlHours = ttlHours;
    }

    public int getLookaheadDays() {
        return lookaheadDays;
    }

    public void setLookaheadDays(int lookaheadDays) {
        this.lookaheadDays = lookaheadDays;
    }

    public String getPreStageCron() {
        return preStageCron;
    }

    public void setPreStageCron(String preStageCron) {
        this.preStageCron = preStageCron;
    }

    public MssqlPool getMssqlPool() {
        return mssqlPool;
    }

    /**
     * HikariCP sizing for the per-source MSSQL pools. Small by design: ingestion is bursty and
     * short-lived, so a few connections per source suffice and idle pools shrink to nothing
     * ({@code minimumIdle = 0}).
     */
    public static class MssqlPool {

        private int maximumPoolSize = 4;
        private int minimumIdle = 0;
        private long connectionTimeoutMs = 5000;

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public int getMinimumIdle() {
            return minimumIdle;
        }

        public void setMinimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
        }

        public long getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public void setConnectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
        }
    }
}
