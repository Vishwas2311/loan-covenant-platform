package com.crisil.covenant.dto;

import java.util.List;
import java.util.Map;

/**
 * Payload posted to the external ML service. Carries the CP's details plus the covenants being
 * onboarded now ({@code covenants}) and, as read-only context, the covenants already applied to the
 * CP ({@code appliedContext}) — the latter are never changed.
 */
public record MlThresholdRequest(
        String cpId,
        Map<String, String> cpAttributes,
        List<MlCovenantContext> covenants,
        List<MlCovenantContext> appliedContext) {
}
