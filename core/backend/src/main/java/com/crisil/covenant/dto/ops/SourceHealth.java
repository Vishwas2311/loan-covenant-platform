package com.crisil.covenant.dto.ops;

import java.time.Instant;

/**
 * The health of a single registered data source, as probed by the production-support health check.
 * Coverage-excluded ({@code dto/**}).
 *
 * @param id              the data source id
 * @param name            the data source name
 * @param type            {@code REST} or {@code MSSQL}
 * @param active          whether the source is currently flagged active
 * @param reachable       {@code true} when the connectivity probe (column discovery) succeeded
 * @param latencyMs       how long the probe took, in milliseconds (measured even on failure)
 * @param columnCount     columns discovered on a successful probe, else {@code 0}
 * @param tableCount      registered {@code cov_data_source_table} rows for this source
 * @param stagingRowCount staged {@code cov_staging_value} rows across all of this source's tables
 * @param lastIngestAt    the most recent ingest instant across this source, or {@code null} if never
 * @param message         a human-readable outcome — the success summary or the probe's error message
 */
public record SourceHealth(
        String id,
        String name,
        String type,
        boolean active,
        boolean reachable,
        long latencyMs,
        int columnCount,
        long tableCount,
        long stagingRowCount,
        Instant lastIngestAt,
        String message) {
}
