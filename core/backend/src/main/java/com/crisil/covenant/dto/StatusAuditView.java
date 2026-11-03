package com.crisil.covenant.dto;

import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.entity.CovenantStatusAudit;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A read view of one append-only status-audit row (US-MON-19 AC4), exposed by the per-CP and
 * per-covenant trail endpoints. Coverage-excluded ({@code dto/**}).
 */
public record StatusAuditView(
        String id,
        String cpId,
        String appliedCovenantId,
        String assessmentId,
        CovenantDispositionStatus oldStatus,
        CovenantDispositionStatus newStatus,
        LocalDate oldExtensionDate,
        LocalDate newExtensionDate,
        CovenantStatusSource source,
        String rationale,
        String changedBy,
        Instant changedDate) {

    public static StatusAuditView from(CovenantStatusAudit a) {
        return new StatusAuditView(a.getId(), a.getCpId(), a.getAppliedCovenantId(), a.getAssessmentId(),
                a.getOldStatus(), a.getNewStatus(), a.getOldExtensionDate(), a.getNewExtensionDate(),
                a.getSource(), a.getRationale(), a.getChangedBy(), a.getChangedDate());
    }
}
