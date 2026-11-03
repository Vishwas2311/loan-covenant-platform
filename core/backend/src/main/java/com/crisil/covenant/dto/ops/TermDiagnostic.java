package com.crisil.covenant.dto.ops;

import com.crisil.covenant.enums.DiagnosticReason;

/**
 * The production-support trace for a single formula term: which measure it references, which staging
 * rows the resolver <em>would</em> read for it (table / entity key / item code, mirroring
 * {@code MeasureAggregationResolver}), what it found there, and the {@link DiagnosticReason}
 * classifying why the term does or does not resolve. Read model only — coverage-excluded
 * ({@code dto/**}).
 *
 * @param measureCode    the measure code referenced by the term
 * @param valueKey       the term's value key (distinguishes different aggregations of one measure)
 * @param aggregation    the aggregation function name, or {@code null} for a bare (LATEST) measure
 * @param window         the canonical window such as {@code 2y}, or {@code null} for point-in-time
 * @param tableId        the backing data-source-table id, or {@code null} when the measure is
 *                       unregistered
 * @param tableName      the backing table's human label, or {@code null} when unregistered
 * @param entityKeyLevel {@code CP_ID} or {@code OBLIGOR_ID} — the grain the table is keyed at
 * @param entityKeyUsed  the entity key the resolver binds (the CP id, or the CP's obligor), or
 *                       {@code null} when an obligor-keyed table has no mapping for the CP
 * @param itemCode       the staging {@code item_code} the term reads ({@code source_column} for a
 *                       COLUMNAR table, {@code source_code} for KEY_VALUE), or {@code null} when
 *                       unregistered
 * @param entityRowCount total rows staged for the entity under this table (across all item codes)
 * @param stagingRowCount rows staged for this term's item code (any as-of date)
 * @param minAsOf        the earliest staged as-of date for the item code (ISO), or {@code null}
 * @param maxAsOf        the latest staged as-of date for the item code (ISO), or {@code null}
 * @param windowLower    the inclusive lower bound of the evaluation window (ISO), or {@code null}
 *                       for an unbounded (point-in-time) term
 * @param windowUpper    the inclusive upper bound (the as-of date) of the evaluation window (ISO)
 * @param windowRowCount rows for the item code that fall inside the evaluation window
 * @param present        whether the resolver would report a value for this term (reason is
 *                       {@link DiagnosticReason#OK})
 * @param reason         the classification of why the term resolves or fails
 * @param detail         a short human-readable explanation for the support engineer
 */
public record TermDiagnostic(
        String measureCode,
        String valueKey,
        String aggregation,
        String window,
        String tableId,
        String tableName,
        String entityKeyLevel,
        String entityKeyUsed,
        String itemCode,
        long entityRowCount,
        long stagingRowCount,
        String minAsOf,
        String maxAsOf,
        String windowLower,
        String windowUpper,
        long windowRowCount,
        boolean present,
        DiagnosticReason reason,
        String detail) {
}
