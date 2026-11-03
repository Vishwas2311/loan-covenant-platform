package com.crisil.covenant.enums;

import com.crisil.covenant.entity.DataSourceTable;

/**
 * The identity grain a {@link DataSourceTable}'s {@code entity_key_column} carries:
 * <ul>
 *   <li>{@link #CP_ID} — the default/legacy level: rows are keyed by CP_ID, so a covenant's
 *       CP_ID binds directly into the source read.</li>
 *   <li>{@link #OBLIGOR_ID} — rows are keyed at obligor grain: a CP's obligor is resolved (via
 *       {@code cov_obligor_cp_map}) and bound instead, so one obligor's data fans out to every
 *       CP under that obligor.</li>
 * </ul>
 * Results always attach back to the CP_ID being assessed — only the source lookup key changes.
 */
public enum TableEntityKeyLevel {
    CP_ID,
    OBLIGOR_ID
}
