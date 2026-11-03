package com.crisil.covenant.dto.ops;

import java.time.Instant;

/**
 * One raw staged row ({@code cov_staging_value}) as shown by the support staging inspector. The typed
 * value columns collapse to a single {@link #value} chosen by {@link #dataType}, matching how the
 * resolver reads it. Coverage-excluded ({@code dto/**}).
 *
 * @param asOfDate      the row's as-of date (ISO)
 * @param itemCode      the staging item code ({@code source_column} / {@code source_code})
 * @param dataType      which typed column holds the value ({@code NUMBER}/{@code STRING}/{@code DATE}
 *                      /{@code BOOLEAN})
 * @param value         the populated typed value (number / string / ISO date / boolean)
 * @param ingestedAt    when the row was staged
 * @param ingestBatchId correlates all rows written by one ingestion pass
 */
public record StagingRowView(
        String asOfDate,
        String itemCode,
        String dataType,
        Object value,
        Instant ingestedAt,
        String ingestBatchId) {
}
