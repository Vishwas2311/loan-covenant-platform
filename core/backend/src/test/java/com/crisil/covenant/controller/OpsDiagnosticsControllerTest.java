package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ops.AssessmentDiagnosticsResponse;
import com.crisil.covenant.dto.ops.CpDiagnosticsResponse;
import com.crisil.covenant.dto.ops.IngestionStateView;
import com.crisil.covenant.dto.ops.StagingInspectResponse;
import com.crisil.covenant.service.OpsDiagnosticsService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpsDiagnosticsControllerTest {

    @Mock private OpsDiagnosticsService service;
    @InjectMocks private OpsDiagnosticsController controller;

    @Test
    void diagnoseCpDelegates() {
        CpDiagnosticsResponse resp = new CpDiagnosticsResponse("CP-1", "OBL-1", 0, List.of());
        when(service.diagnoseCp("CP-1")).thenReturn(resp);

        assertThat(controller.diagnoseCp("CP-1")).isSameAs(resp);
        verify(service).diagnoseCp("CP-1");
    }

    @Test
    void diagnoseAssessmentDelegates() {
        AssessmentDiagnosticsResponse resp = new AssessmentDiagnosticsResponse("a1", "ac1", "CP-1",
                "ERROR", "COMPLETED", "Missing values for measures: [DSCR]",
                Instant.parse("2026-01-01T00:00:00Z"), null);
        when(service.diagnoseAssessment("a1")).thenReturn(resp);

        assertThat(controller.diagnoseAssessment("a1")).isSameAs(resp);
        verify(service).diagnoseAssessment("a1");
    }

    @Test
    void inspectStagingDelegates() {
        StagingInspectResponse resp = new StagingInspectResponse("CP-1", "t1", "T", "CP_ID", "CP-1",
                0, List.of());
        when(service.inspectStaging("CP-1", "t1")).thenReturn(resp);

        assertThat(controller.inspectStaging("CP-1", "t1")).isSameAs(resp);
        verify(service).inspectStaging("CP-1", "t1");
    }

    @Test
    void ingestionStateDelegates() {
        IngestionStateView v = new IngestionStateView("t1", "T", "CP_ID", "CP-1", true, "2025-01-01",
                null, null, null, "OK", 5L, false, "fresh");
        when(service.ingestionStates("CP-1")).thenReturn(List.of(v));

        assertThat(controller.ingestionState("CP-1")).containsExactly(v);
        verify(service).ingestionStates("CP-1");
    }
}
