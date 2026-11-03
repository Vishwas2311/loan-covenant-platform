package com.crisil.covenant.dto.ops;

import java.time.Instant;
import java.util.List;

/**
 * The rolled-up health of every probed data source. Backs both the gated
 * {@code /api/v1/ops/health/data-sources} view and the {@code dataSources} Actuator health indicator,
 * so the two can never diverge. Coverage-excluded ({@code dto/**}).
 *
 * @param checkedAt      when the probe ran
 * @param sourceCount    how many sources were probed
 * @param reachableCount how many of them were reachable
 * @param allReachable   {@code true} when every probed source was reachable (vacuously true if none)
 * @param sources        the per-source detail, in name order
 */
public record HealthReport(
        Instant checkedAt,
        int sourceCount,
        int reachableCount,
        boolean allReachable,
        List<SourceHealth> sources) {
}
