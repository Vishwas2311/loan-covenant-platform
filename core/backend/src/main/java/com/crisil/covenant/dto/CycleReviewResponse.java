package com.crisil.covenant.dto;

import java.util.List;

/**
 * The read-only assessment view of one CP in a lineage (US-MON-23): the same per-covenant review rows
 * shown on the live assessment grid (US-MON-16) plus the CP-level compliance certificates (US-MON-17),
 * tagged with the CP's cycle number and (best-effort) display name. Strictly view-only — no disposition,
 * upload or remove is offered on this payload. Read model only — coverage-excluded ({@code dto/**}).
 */
public record CycleReviewResponse(
        String cpId,
        int cycleNumber,
        String cpName,
        List<CpReviewRow> review,
        List<AttachmentView> certificates) {
}
