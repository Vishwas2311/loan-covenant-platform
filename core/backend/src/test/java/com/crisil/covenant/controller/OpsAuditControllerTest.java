package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ops.NotificationAuditView;
import com.crisil.covenant.dto.ops.OpsAuditView;
import com.crisil.covenant.entity.NotificationAudit;
import com.crisil.covenant.repository.NotificationAuditRepository;
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
    @Mock private NotificationAuditRepository notifications;
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

    @Test
    void sentNotificationsMapsTheLatestRows() {
        NotificationAudit sent = mock(NotificationAudit.class);
        when(sent.getId()).thenReturn("n1");
        when(sent.getEmailTo()).thenReturn("rm-anil@bank.test");
        when(sent.getOutcome()).thenReturn(NotificationAudit.Outcome.SUCCESS);
        when(notifications.findTop100ByOrderByChangedDateDesc()).thenReturn(List.of(sent));

        List<NotificationAuditView> rows = controller.sentNotifications();

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo("n1");
            assertThat(r.emailTo()).isEqualTo("rm-anil@bank.test");
            assertThat(r.outcome()).isEqualTo("SUCCESS");
        });
    }

    private static OpsAuditView view(String id) {
        return new OpsAuditView(id, "reingest", "cp", "CP-1", "alice", "tableId=T1",
                "SUCCESS", "done", Instant.parse("2026-07-25T10:15:30Z"));
    }
}
