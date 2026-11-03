package com.crisil.covenant.dto.ops;

/**
 * Body for the destructive CP data-cleanup endpoint. The caller must echo the exact {@code cpId} from
 * the path in {@link #confirmCpId()} as a deliberate confirmation gesture; a mismatch is rejected with
 * {@code 400 CP_CONFIRM_MISMATCH} before any data is touched.
 */
public record CpCleanupRequest(String confirmCpId) {
}
