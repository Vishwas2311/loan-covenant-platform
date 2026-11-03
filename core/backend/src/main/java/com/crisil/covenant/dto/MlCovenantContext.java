package com.crisil.covenant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One covenant sent to the ML service: its master id, its {@code covenantName}, the current effective
 * threshold expression ({@code currentExpression} — the per-CP override if set, otherwise the covenant
 * master's condition) and the {@code thresholds} extracted from that expression (label + operator +
 * value per tunable constant). Used both for the in-scope covenants being tuned and for the read-only
 * already-applied context.
 *
 * <p>The onboarding wizard sends {@code covenantMasterId}, {@code covenantName} and
 * {@code currentExpression}; {@code thresholds} is populated server-side (via the formula engine)
 * before the request is forwarded to the ML service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlCovenantContext(
        @NotNull String covenantMasterId,
        String covenantName,
        String currentExpression,
        List<MlThresholdSlot> thresholds) {
}
