package com.crisil.covenant.dto.ops;

import java.util.List;
import java.util.Map;

/**
 * Outcome of a destructive CP data cleanup. {@link #deletedByTable()} is an insertion-ordered map of
 * {@code cov_*} table name to the number of rows removed from it (in FK child-to-parent order), so the
 * support engineer sees exactly what was purged. {@link #warnings()} carries any non-fatal notes — most
 * importantly the obligor-shared-staging guard, which leaves obligor-grain rows intact when sibling CPs
 * still depend on them.
 */
public record CpCleanupResult(String cpId, Map<String, Long> deletedByTable, List<String> warnings) {
}
