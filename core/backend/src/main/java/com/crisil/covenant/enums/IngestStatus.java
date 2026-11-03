package com.crisil.covenant.enums;

/**
 * US-DS-13: the outcome of the most recent ingestion decision recorded in {@code cov_ingestion_state}.
 * <ul>
 *   <li>{@link #OK} — the last extract landed successfully; the staged watermark is trustworthy.</li>
 *   <li>{@link #SKIPPED} — the freshness gate decided the staged copy already satisfied demand, so no
 *       extract was issued.</li>
 *   <li>{@link #FAILED} — the last extract raised; the entity is stale and eligible for independent
 *       retry without blocking others.</li>
 * </ul>
 */
public enum IngestStatus {
    OK,
    SKIPPED,
    FAILED
}
