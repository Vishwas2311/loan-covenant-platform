package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.dto.BreachReportRow;
import com.crisil.covenant.dto.NotificationRequest;
import com.crisil.covenant.dto.NotificationResult;
import com.crisil.covenant.entity.NotificationAudit;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.repository.NotificationAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** FR-31: unhandled breaches past the limit go to the configured recipients in one audited digest. */
class EscalationServiceTest {

    private final MonitoringService monitoring = mock(MonitoringService.class);
    private final BreachReportService breaches = mock(BreachReportService.class);
    private final NotificationClient client = mock(NotificationClient.class);
    private final NotificationAuditRepository audits = mock(NotificationAuditRepository.class);

    private EscalationService service(String recipients) {
        return new EscalationService(monitoring, breaches, client, audits, new ObjectMapper().findAndRegisterModules(),
                recipients, 14);
    }

    private static BreachReportRow row(String cp, boolean escalate) {
        return new BreachReportRow(cp, "ac-" + cp, "Minimum DSCR", AssessmentResult.BREACHED, "1.05",
                LocalDate.of(2026, 9, 30), escalate ? 36 : 3, CovenantDispositionStatus.BREACHED, null, escalate);
    }

    @Test
    void onlyEscalatedBreachesAreSentAndTheSendIsAudited() {
        List<CpMonitoringSummary> bank = List.of(new CpMonitoringSummary("CP-1", 1, 0, 1, 0, 0, 0));
        when(monitoring.allSummary()).thenReturn(bank);
        when(breaches.openBreachesFor(bank, 14)).thenReturn(List.of(row("CP-1", true), row("CP-2", false)));
        when(client.send(any())).thenReturn(new NotificationResult(true, 200, "{}", null));

        assertThat(service("credit.head@bank.test").run("system")).isEqualTo(1);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(client).send(sent.capture());
        assertThat(sent.getValue().templateCode()).isEqualTo(EscalationService.TEMPLATE);
        assertThat(sent.getValue().emailTo()).isEqualTo("credit.head@bank.test");
        assertThat(sent.getValue().variables().cpMonitoring()).singleElement()
                .satisfies(m -> assertThat(m).containsEntry("CP_ID", "CP-1").containsEntry("Days_Open", "36"));
        ArgumentCaptor<NotificationAudit> audit = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(audits).save(audit.capture());
        assertThat(audit.getValue().getOutcome()).isEqualTo(NotificationAudit.Outcome.SUCCESS);
    }

    @Test
    void aFailedSendIsAuditedAsFailed() {
        when(monitoring.allSummary()).thenReturn(List.of());
        when(breaches.openBreachesFor(List.of(), 14)).thenReturn(List.of(row("CP-1", true)));
        when(client.send(any())).thenReturn(new NotificationResult(false, 503, null, "down"));

        assertThat(service("credit.head@bank.test").run("system")).isEqualTo(1);

        ArgumentCaptor<NotificationAudit> audit = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(audits).save(audit.capture());
        assertThat(audit.getValue().getOutcome()).isEqualTo(NotificationAudit.Outcome.FAILED);
    }

    @Test
    void nothingIsSentWithoutRecipientsOrWithoutEscalations() {
        assertThat(service(" ").run("system")).isZero();
        verify(monitoring, never()).allSummary();

        when(monitoring.allSummary()).thenReturn(List.of());
        when(breaches.openBreachesFor(List.of(), 14)).thenReturn(List.of(row("CP-2", false)));
        assertThat(service("credit.head@bank.test").run("system")).isZero();
        verify(client, never()).send(any());
    }
}
