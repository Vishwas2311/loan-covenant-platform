package com.crisil.covenant.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ops.HealthReport;
import com.crisil.covenant.dto.ops.SourceHealth;
import com.crisil.covenant.service.OpsHealthService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Unit tests for {@link DataSourceHealthIndicator}: it maps the {@link OpsHealthService} roll-up to an
 * Actuator {@link Health} — UP with source/reachable counts when everything is reachable, DOWN with the
 * unreachable source names otherwise.
 */
@ExtendWith(MockitoExtension.class)
class DataSourceHealthIndicatorTest {

    @Mock private OpsHealthService healthService;
    @InjectMocks private DataSourceHealthIndicator indicator;

    @Test
    void reportsUpWithNoUnreachableDetailWhenAllSourcesReachable() {
        SourceHealth ok = new SourceHealth("ds-1", "Alpha", "REST", true, true, 5L, 3, 2L, 10L,
                Instant.now(), "Connected — discovered 3 column(s)");
        when(healthService.checkDataSources(true))
                .thenReturn(new HealthReport(Instant.now(), 1, 1, true, List.of(ok)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("sources", 1)
                .containsEntry("reachable", 1)
                .doesNotContainKey("unreachable");
    }

    @Test
    void reportsDownWithUnreachableNamesWhenAnySourceFails() {
        SourceHealth up = new SourceHealth("ds-1", "Alpha", "REST", true, true, 5L, 3, 2L, 10L,
                Instant.now(), "ok");
        SourceHealth down = new SourceHealth("ds-2", "Beta", "MSSQL", true, false, 7L, 0, 0L, 0L,
                null, "connection refused");
        when(healthService.checkDataSources(true))
                .thenReturn(new HealthReport(Instant.now(), 2, 1, false, List.of(up, down)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("sources", 2)
                .containsEntry("reachable", 1);
        assertThat(health.getDetails().get("unreachable")).isEqualTo(List.of("Beta"));
    }
}
