package com.crisil.covenant.dto.ops;

import java.util.List;

/**
 * The raw staged rows a support engineer sees when inspecting one {@code (CP, table)} pair. The
 * entity key is resolved the same way the resolver would (the CP id, or the CP's obligor for an
 * obligor-keyed table), so the inspector shows exactly the rows an assessment reads. Coverage-excluded
 * ({@code dto/**}).
 *
 * @param cpId           the Credit Proposal id
 * @param tableId        the inspected data-source-table id
 * @param tableName      the table's human label
 * @param entityKeyLevel {@code CP_ID} or {@code OBLIGOR_ID}
 * @param entityKeyUsed  the entity key the rows are read under, or {@code null} for an obligor-keyed
 *                       table with no mapping for the CP (then {@link #rows} is empty)
 * @param rowCount       the number of rows returned
 * @param rows           the staged rows, newest as-of date first
 */
public record StagingInspectResponse(
        String cpId,
        String tableId,
        String tableName,
        String entityKeyLevel,
        String entityKeyUsed,
        int rowCount,
        List<StagingRowView> rows) {
}
