package com.crisil.covenant.dto;

/**
 * One CP the caller looks after, as the CP chooser shows it: who the borrower is, where it sits and how
 * its covenants stand. {@code name} is {@code null} when the CP service cannot be reached; the counts are
 * zero for a CP with no covenants yet (it still needs onboarding).
 */
public record CpCard(String cpId, String name, String businessSegment, String sector, String rmUserId,
                     long applied, long compliant, long breached, long error, long pendingReview,
                     long dispositioned) {
}
