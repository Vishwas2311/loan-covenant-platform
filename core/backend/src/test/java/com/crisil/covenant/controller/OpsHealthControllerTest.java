package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ops.HealthReport;
import com.crisil.covenant.service.OpsHealthService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link OpsHealthController}: the endpoint simply forwards the {@code activeOnly} flag
 * (defaulting true at the binding layer) to {@link OpsHealthService} and returns its report.
 */
@ExtendWith(MockitoExtension.class)
class OpsHealthControllerTest {

    @Mock private OpsHealthService service;
    @InjectMocks private OpsHealthController controller;

    @Test
    void dataSourceHealthDelegatesWithActiveOnlyTrue() {
        HealthReport report = new HealthReport(Instant.now(), 1, 1, true, List.of());
        when(service.checkDataSources(true)).thenReturn(report);

        assertThat(controller.dataSourceHealth(true)).isSameAs(report);
        verify(service).checkDataSources(true);
    }

    @Test
    void dataSourceHealthDelegatesWithActiveOnlyFalse() {
        HealthReport report = new HealthReport(Instant.now(), 2, 1, false, List.of());
        when(service.checkDataSources(false)).thenReturn(report);

        assertThat(controller.dataSourceHealth(false)).isSameAs(report);
        verify(service).checkDataSources(false);
    }
}
