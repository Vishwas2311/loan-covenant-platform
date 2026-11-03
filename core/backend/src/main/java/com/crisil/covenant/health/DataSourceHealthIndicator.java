package com.crisil.covenant.health;

import com.crisil.covenant.dto.ops.HealthReport;
import com.crisil.covenant.dto.ops.SourceHealth;
import com.crisil.covenant.service.OpsHealthService;
import java.util.List;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes a {@code dataSources} entry to the Actuator health endpoint: UP when every active data
 * source is reachable, DOWN (carrying the unreachable source names) otherwise. It delegates to
 * {@link OpsHealthService} so the Actuator roll-up and the gated
 * {@code /api/v1/ops/health/data-sources} detail view can never diverge. The probe is read-only.
 */
@Component("dataSources")
public class DataSourceHealthIndicator implements HealthIndicator {

    private final OpsHealthService healthService;

    public DataSourceHealthIndicator(OpsHealthService healthService) {
        this.healthService = healthService;
    }

    @Override
    public Health health() {
        HealthReport report = healthService.checkDataSources(true);
        Health.Builder builder = report.allReachable() ? Health.up() : Health.down();
        builder.withDetail("sources", report.sourceCount())
                .withDetail("reachable", report.reachableCount());
        List<String> unreachable = report.sources().stream()
                .filter(source -> !source.reachable())
                .map(SourceHealth::name)
                .toList();
        if (!unreachable.isEmpty()) {
            builder.withDetail("unreachable", unreachable);
        }
        return builder.build();
    }
}
