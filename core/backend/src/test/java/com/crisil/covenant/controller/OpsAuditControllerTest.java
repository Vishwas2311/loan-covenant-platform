package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ops.OpsAuditView;
import com.crisil.covenant.service.OpsAuditService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link OpsAuditController}: the endpoint simply forwards {@code targetType} and the
 * optional {@code targetId} to {@link OpsAuditService#trail} and returns the trail unchanged.
 */
@ExtendWith(MockitoExtension.class)
class OpsAuditControllerTest {

    @Mock private OpsAuditService service;
    @InjectMocks private OpsAuditController controller;

    @Test
    void auditTrailDelegatesWithTargetId() {
        List<OpsAuditView> trail = List.of(view("a1"));
        when(service.trail("cp", "CP-1")).thenReturn(trail);

        assertThat(controller.auditTrail("cp", "CP-1")).isSameAs(trail);
        verify(service).trail("cp", "CP-1");
    }

    @Test
    void auditTrailDelegatesWithoutTargetId() {
        List<OpsAuditView> trail = List.of(view("a1"), view("a2"));
        when(service.trail("cp", null)).thenReturn(trail);

        assertThat(controller.auditTrail("cp", null)).isSameAs(trail);
        verify(service).trail("cp", null);
    }

    private static OpsAuditView view(String id) {
        return new OpsAuditView(id, "reingest", "cp", "CP-1", "alice", "tableId=T1",
                "SUCCESS", "done", Instant.parse("2026-07-25T10:15:30Z"));
    }
}
