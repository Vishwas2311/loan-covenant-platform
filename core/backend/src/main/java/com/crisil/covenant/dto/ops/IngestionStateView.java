package com.crisil.covenant.dto.ops;

import java.time.Instant;

/**
 * A read-only view of the freshness-gate control row ({@code cov_ingestion_state}) for one
 * {@code (table, entity)} pair the CP touches, plus the gate's <em>current</em> verdict. It surfaces
 * the exact inputs behind the stale-ingestion-state incident: a row that claims fresh
 * ({@link #wouldIngest} false) while {@link #stagingRowCount} is zero is the bug — the copy was
 * cleared but the gate still believes it is current. Coverage-excluded ({@code dto/**}).
 *
 * @param tableId          the data-source-table id
 * @param tableName        the table's human label
 * @param entityKeyLevel   {@code CP_ID} or {@code OBLIGOR_ID}
 * @param entityKeyUsed    the entity key the control row is (or would be) keyed by, or {@code null}
 *                         for an obligor-keyed table with no mapping for the CP
 * @param present          whether a control row actually exists for the entity
 * @param stagedAsOf       the watermark (max staged as-of date) recorded on the row (ISO), or
 *                         {@code null}
 * @param stagedIngestedAt when that copy was written, or {@code null}
 * @param sourceChangedAt  when the source last reported a change / was force-refreshed, or
 *                         {@code null}
 * @param nextEligibleAt   the TTL floor for the next re-pull, or {@code null}
 * @param lastStatus       the outcome of the most recent decision (e.g. {@code OK}, {@code SKIPPED},
 *                         {@code FAILED}), or {@code null} when no row exists
 * @param stagingRowCount  rows currently staged for the entity under this table
 * @param wouldIngest      what the freshness gate would decide right now ({@code true} = re-ingest)
 * @param decideReason     the gate's reason code ({@code missing}/{@code stale}/{@code source-changed}
 *                         /{@code ttl}/{@code fresh})
 */
public record IngestionStateView(
        String tableId,
        String tableName,
        String entityKeyLevel,
        String entityKeyUsed,
        boolean present,
        String stagedAsOf,
        Instant stagedIngestedAt,
        Instant sourceChangedAt,
        Instant nextEligibleAt,
        String lastStatus,
        long stagingRowCount,
        boolean wouldIngest,
        String decideReason) {
}
