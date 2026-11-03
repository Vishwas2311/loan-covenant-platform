package com.crisil.covenant.dto;

import com.crisil.covenant.entity.ThresholdAudit;
import com.crisil.covenant.enums.ThresholdSource;
import java.time.Instant;

/**
 * One row of an applied covenant's per-CP definition change log (US-CM-25 AC7): the master expression
 * it was changed from, the per-CP expression it was changed to, and who committed it when.
 */
public record DefinitionChangeRow(
        Instant changedAt,
        String changedBy,
        String oldExpression,
        String newExpression,
        ThresholdSource source) {

    public static DefinitionChangeRow from(ThresholdAudit audit) {
        return new DefinitionChangeRow(
                audit.getChangedDate(),
                audit.getChangedBy(),
                audit.getOldExpression(),
                audit.getNewExpression(),
                audit.getSource());
    }
}
