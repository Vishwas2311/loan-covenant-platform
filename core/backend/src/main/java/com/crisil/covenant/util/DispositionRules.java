package com.crisil.covenant.util;

import com.crisil.covenant.entity.CovenantDisposition;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.DispositionApproval;

/**
 * Shared rule for when a recorded disposition (US-MON-16) still counts. A Waiver/Extension only
 * "addresses the current breach" while it is pinned to the covenant's <em>latest</em> assessment;
 * once a newer assessment supersedes it the disposition lapses and the covenant reads as an open
 * breach again.
 *
 * <p>Extracted so the CP assessment grid ({@link com.crisil.covenant.service.ReviewService}) and the
 * portfolio monitoring roll-ups ({@link com.crisil.covenant.service.MonitoringService} — the By-CP
 * summary handled-count of US-MON-20 and the By-Covenant agenda badges of US-MON-21) share one
 * definition of an "active" disposition.
 */
public final class DispositionRules {

    private DispositionRules() {}

    /**
     * Whether {@code disp} is an active Waiver/Extension addressing the breach identified by
     * {@code latestAssessmentId}: it must exist, be pinned to that latest assessment, and be a
     * WAIVER or EXTENSION (COMPLIED/BREACHED are derived states, not dispositions), and be APPROVED
     * (FR-12: a REQUESTED or REJECTED disposition leaves the breach standing).
     */
    public static boolean addressesCurrentBreach(CovenantDisposition disp, String latestAssessmentId) {
        return disp != null
                && disp.getAssessmentId() != null
                && disp.getAssessmentId().equals(latestAssessmentId)
                && (disp.getStatus() == CovenantDispositionStatus.WAIVER
                    || disp.getStatus() == CovenantDispositionStatus.EXTENSION)
                && disp.getApprovalStatus() == DispositionApproval.APPROVED;
    }
}
