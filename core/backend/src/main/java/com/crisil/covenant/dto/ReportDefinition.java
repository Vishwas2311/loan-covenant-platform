package com.crisil.covenant.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * C11: what a custom report shows. {@code columns} picks from {@link #COLUMNS} (empty = all, in that
 * order); {@code results} keeps only covenants whose result is one of them (AT_RISK means compliant
 * but close to the limit; empty = all); {@code segment} keeps one business segment; {@code cpFilter}
 * keeps CPs whose id contains it.
 */
public record ReportDefinition(
        @Size(max = 20) List<String> columns,
        @Size(max = 10) List<String> results,
        @Size(max = 64) String segment,
        @Size(max = 64) String cpFilter) {

    public static final List<String> COLUMNS = List.of(
            "cpId", "segment", "covenantName", "rule", "value", "headroom", "result", "atRisk", "status",
            "approval", "nextDueDate", "overdueDays");

    public List<String> chosenColumns() {
        List<String> picked = columns == null ? List.of()
                : columns.stream().filter(COLUMNS::contains).distinct().toList();
        return picked.isEmpty() ? COLUMNS : COLUMNS.stream().filter(picked::contains).toList();
    }
}
