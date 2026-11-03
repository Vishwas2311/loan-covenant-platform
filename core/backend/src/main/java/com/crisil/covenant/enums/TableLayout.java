package com.crisil.covenant.enums;
import com.crisil.covenant.entity.DataSourceTable;

/**
 * Physical shape of a {@link DataSourceTable}, deciding how a measure maps to storage:
 * <ul>
 *   <li>{@link #COLUMNAR} — the default/legacy layout: each measure is its own physical column
 *       (a measure carries a {@code sourceColumn}).</li>
 *   <li>{@link #KEY_VALUE} — a row / long / EAV layout: one <em>value</em> column holds many
 *       measures, distinguished by a <em>code</em> column (a measure carries a {@code sourceCode}
 *       matched against that code column).</li>
 * </ul>
 * The covenant formula/assessment layer is unaffected — it works purely on measure {@code code}
 * → value; only the mapping to physical storage and the fetch SQL differ.
 */
public enum TableLayout {
    COLUMNAR,
    KEY_VALUE
}
