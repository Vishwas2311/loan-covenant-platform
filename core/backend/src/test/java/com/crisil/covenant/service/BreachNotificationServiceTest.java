package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.repository.AssessmentRepository.AppliedCovenantLastExecuted;
import com.crisil.covenant.service.AssessmentService;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.domain.CpUser;
import com.crisil.covenant.service.CreditProposalClient;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.service.MonitoringService;
import com.crisil.covenant.dto.NotificationRequest;
import com.crisil.covenant.dto.NotificationResult;
import com.crisil.covenant.dto.RunResult;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.web.context.request.RequestContextHolder;
import com.crisil.covenant.entity.NotificationAudit;
import com.crisil.covenant.repository.NotificationAuditRepository;

@ExtendWith(MockitoExtension.class)
class BreachNotificationServiceTest {

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private AssessmentRepository assessmentRepo;
    @Mock private AssessmentService assessmentService;
    @Mock private MonitoringService monitoringService;
    @Mock private CreditProposalClient creditProposalClient;
    @Mock private NotificationClient notificationClient;
    @Mock private NotificationAuditRepository auditRepo;
    @InjectMocks private BreachNotificationService service;

    /** Started long ago and never assessed → OVERDUE → due on any run date. */
    private static final LocalDate LONG_AGO = LocalDate.now().minusYears(5);
    /** Starts well beyond the run date → NOT_STARTED, next due in the future → not due. */
    private static final LocalDate FUTURE = LocalDate.now().plusDays(30);

    @Test
    void assessesDueCovenantsAndSkipsNotDueAndNonActive() {
        AppliedCovenant due = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        AppliedCovenant notDue = applied("2", "CP-2", FUTURE, AppliedCovenantStatus.ACTIVE);
        // Non-active covenants never reach the service now — findByStatus(ACTIVE) filters them out.
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(due, notDue));
        when(monitoringService.summaryFor("CP-1")).thenReturn(summary("CP-1", 4, 1, 3, 0, 0));
        when(creditProposalClient.lookup("CP-1")).thenReturn(details("CP-1", "rm@x", "wrm@x"));
        when(notificationClient.send(any())).thenReturn(ok());

        // Explicit (non-null) run date exercises the asOf branch; behaves like today.
        RunResult result = service.run(LocalDate.now(), "tester");

        verify(assessmentService).runAssessment(eq("1"), eq(AssessmentTrigger.SCHEDULED), eq("tester"), any());
        verify(assessmentService, never()).runAssessment(eq("2"), any(), any(), any());
        verify(assessmentService, never()).runAssessment(eq("3"), any(), any(), any());
        assertThat(result.covenantsAssessed()).isEqualTo(1);
        assertThat(result.cpsTouched()).isEqualTo(1);
        assertThat(result.notificationsSent()).isEqualTo(1);
        assertThat(result.notificationsFailed()).isZero();
        assertThat(result.runId()).isNotBlank();
    }

    @Test
    void mergesCpsSharingRmWrmPairIntoOneEmail() {
        AppliedCovenant c1 = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        AppliedCovenant c2 = applied("2", "CP-2", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c1, c2));
        when(monitoringService.summaryFor("CP-1")).thenReturn(summary("CP-1", 5, 2, 3, 0, 0));
        when(monitoringService.summaryFor("CP-2")).thenReturn(summary("CP-2", 1, 0, 0, 1, 0));
        when(creditProposalClient.lookup("CP-1")).thenReturn(details("CP-1", "anil@x", "meera@x"));
        when(creditProposalClient.lookup("CP-2")).thenReturn(details("CP-2", "anil@x", "meera@x"));
        when(notificationClient.send(any())).thenReturn(ok());

        RunResult result = service.run(null, "tester");

        ArgumentCaptor<NotificationRequest> reqCaptor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationClient).send(reqCaptor.capture()); // exactly one send for the shared pair
        NotificationRequest req = reqCaptor.getValue();
        assertThat(req.moduleCode()).isEqualTo("COV");
        assertThat(req.templateCode()).isEqualTo("Cov_Template");
        assertThat(req.priority()).isEqualTo("HIGH");
        assertThat(req.emailTo()).isEqualTo("anil@x");   // RM → emailTo
        assertThat(req.emailCC()).isEqualTo("meera@x");  // WRM → emailCC
        assertThat(req.variables().cpMonitoring()).hasSize(2);
        Map<String, String> row1 = req.variables().cpMonitoring().get(0);
        assertThat(row1).containsEntry("CP_ID", "CP-1")
                .containsEntry("Applied", "5")
                .containsEntry("Compliant", "2")
                .containsEntry("Breached", "3")
                .containsEntry("Error", "0")
                .containsEntry("Pending", "0");
        assertThat(req.variables().cpMonitoring().get(1)).containsEntry("CP_ID", "CP-2");

        assertThat(result.covenantsAssessed()).isEqualTo(2);
        assertThat(result.cpsTouched()).isEqualTo(2);
        assertThat(result.notificationsSent()).isEqualTo(1);

        ArgumentCaptor<NotificationAudit> auditCaptor = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(auditRepo).save(auditCaptor.capture());
        NotificationAudit audit = auditCaptor.getValue();
        assertThat(audit.getOutcome()).isEqualTo(NotificationAudit.Outcome.SUCCESS);
        assertThat(audit.getEmailTo()).isEqualTo("anil@x");
        assertThat(audit.getEmailCc()).isEqualTo("meera@x");
        assertThat(audit.getCpIds()).isEqualTo("CP-1,CP-2");
        assertThat(audit.getRunId()).isEqualTo(result.runId());
        assertThat(audit.getRequestBody()).contains("CP-1", "CP-2", "Cov_Template");
        assertThat(audit.getChangedBy()).isEqualTo("tester");
        assertThat(audit.getHttpStatus()).isEqualTo(200);
    }

    @Test
    void cpWithoutRmIsAssessedButAuditedAsNoRecipient() {
        AppliedCovenant c = applied("9", "CP-9", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c));
        when(monitoringService.summaryFor("CP-9")).thenReturn(summary("CP-9", 2, 0, 2, 0, 0));
        when(creditProposalClient.lookup("CP-9")).thenReturn(details("CP-9", null, "wrm@x"));

        RunResult result = service.run(null, "tester");

        verify(notificationClient, never()).send(any());
        assertThat(result.covenantsAssessed()).isEqualTo(1);
        assertThat(result.cpsTouched()).isEqualTo(1);
        assertThat(result.notificationsSent()).isZero();
        assertThat(result.notificationsFailed()).isEqualTo(1);

        ArgumentCaptor<NotificationAudit> auditCaptor = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(auditRepo).save(auditCaptor.capture());
        NotificationAudit audit = auditCaptor.getValue();
        assertThat(audit.getOutcome()).isEqualTo(NotificationAudit.Outcome.FAILED);
        assertThat(audit.getEmailTo()).isNull();
        assertThat(audit.getCpIds()).isEqualTo("CP-9");
        assertThat(audit.getErrorMessage()).contains("No recipient");
    }

    @Test
    void clientFailureIsCountedAndBatchContinues() {
        AppliedCovenant c1 = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        AppliedCovenant c2 = applied("2", "CP-2", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c1, c2));
        when(monitoringService.summaryFor("CP-1")).thenReturn(summary("CP-1", 1, 0, 1, 0, 0));
        when(monitoringService.summaryFor("CP-2")).thenReturn(summary("CP-2", 1, 1, 0, 0, 0));
        when(creditProposalClient.lookup("CP-1")).thenReturn(details("CP-1", "rmA@x", null));
        when(creditProposalClient.lookup("CP-2")).thenReturn(details("CP-2", "rmB@x", null));
        when(notificationClient.send(any()))
                .thenReturn(new NotificationResult(false, 500, "boom", "5xx"))
                .thenReturn(ok());

        RunResult result = service.run(null, "tester");

        assertThat(result.notificationsSent()).isEqualTo(1);
        assertThat(result.notificationsFailed()).isEqualTo(1);
        verify(notificationClient, times(2)).send(any()); // distinct pairs → two emails, batch continued
        ArgumentCaptor<NotificationAudit> auditCaptor = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(auditRepo, times(2)).save(auditCaptor.capture());
        List<NotificationAudit> audits = auditCaptor.getAllValues();
        assertThat(audits).anyMatch(a -> a.getOutcome() == NotificationAudit.Outcome.FAILED
                && a.getHttpStatus() != null && a.getHttpStatus() == 500);
        assertThat(audits).anyMatch(a -> a.getOutcome() == NotificationAudit.Outcome.SUCCESS);
    }

    @Test
    void nullResultFromClientIsAuditedAsFailed() {
        AppliedCovenant c = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c));
        when(monitoringService.summaryFor("CP-1")).thenReturn(summary("CP-1", 1, 0, 1, 0, 0));
        when(creditProposalClient.lookup("CP-1")).thenReturn(details("CP-1", "rm@x", "wrm@x"));
        when(notificationClient.send(any())).thenReturn(null);

        RunResult result = service.run(null, "tester");

        assertThat(result.notificationsFailed()).isEqualTo(1);
        assertThat(result.notificationsSent()).isZero();
        ArgumentCaptor<NotificationAudit> auditCaptor = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(auditRepo).save(auditCaptor.capture());
        NotificationAudit audit = auditCaptor.getValue();
        assertThat(audit.getOutcome()).isEqualTo(NotificationAudit.Outcome.FAILED);
        assertThat(audit.getHttpStatus()).isNull();
        assertThat(audit.getResponseBody()).isNull();
        assertThat(audit.getErrorMessage()).contains("No response");
    }

    @Test
    void cpLookupFailureIsTreatedAsNoRecipient() {
        AppliedCovenant c = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c));
        when(monitoringService.summaryFor("CP-1")).thenReturn(summary("CP-1", 1, 0, 1, 0, 0));
        when(creditProposalClient.lookup("CP-1")).thenThrow(new RuntimeException("cp source down"));

        RunResult result = service.run(null, "tester");

        verify(notificationClient, never()).send(any());
        assertThat(result.notificationsFailed()).isEqualTo(1);
        ArgumentCaptor<NotificationAudit> auditCaptor = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(auditRepo).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOutcome()).isEqualTo(NotificationAudit.Outcome.FAILED);
        assertThat(auditCaptor.getValue().getCpIds()).isEqualTo("CP-1");
    }

    @Test
    void assessmentFailureDoesNotAbortRun() {
        AppliedCovenant c1 = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        AppliedCovenant c2 = applied("2", "CP-2", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c1, c2));
        when(assessmentService.runAssessment(eq("1"), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(monitoringService.summaryFor("CP-2")).thenReturn(summary("CP-2", 1, 0, 1, 0, 0));
        when(creditProposalClient.lookup("CP-2")).thenReturn(details("CP-2", "rm@x", "wrm@x"));
        when(notificationClient.send(any())).thenReturn(ok());

        RunResult result = service.run(null, "tester");

        assertThat(result.covenantsAssessed()).isEqualTo(1);   // only CP-2 succeeded
        assertThat(result.cpsTouched()).isEqualTo(1);
        assertThat(result.notificationsSent()).isEqualTo(1);
        verify(monitoringService, never()).summaryFor("CP-1"); // failed covenant's CP not touched
    }

    @Test
    void runWithNothingDueSendsNothing() {
        AppliedCovenant notDue = applied("1", "CP-1", FUTURE, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(notDue));

        RunResult result = service.run(null, "tester");

        assertThat(result.covenantsAssessed()).isZero();
        assertThat(result.cpsTouched()).isZero();
        assertThat(result.notificationsSent()).isZero();
        assertThat(result.notificationsFailed()).isZero();
        verify(auditRepo, never()).save(any());
        verify(notificationClient, never()).send(any());
        verify(assessmentService, never()).runAssessment(any(), any(), any(), any());
    }

    @Test
    void nullTriggeredByFallsBackToSystemUser() {
        RequestContextHolder.resetRequestAttributes(); // no request context → CurrentUserContext empty
        AppliedCovenant c = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c));
        when(monitoringService.summaryFor("CP-1")).thenReturn(summary("CP-1", 1, 0, 1, 0, 0));
        when(creditProposalClient.lookup("CP-1")).thenReturn(details("CP-1", "rm@x", "wrm@x"));
        when(notificationClient.send(any())).thenReturn(ok());

        service.run(null, null);

        verify(assessmentService).runAssessment(eq("1"), eq(AssessmentTrigger.SCHEDULED), eq("system"), any());
        ArgumentCaptor<NotificationAudit> auditCaptor = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(auditRepo).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getChangedBy()).isEqualTo("system");
    }

    @Test
    void blankTriggeredByFallsBackToSystemUser() {
        RequestContextHolder.resetRequestAttributes(); // no request context → CurrentUserContext empty
        AppliedCovenant c = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c));
        when(monitoringService.summaryFor("CP-1")).thenReturn(summary("CP-1", 1, 0, 1, 0, 0));
        when(creditProposalClient.lookup("CP-1")).thenReturn(details("CP-1", "rm@x", "wrm@x"));
        when(notificationClient.send(any())).thenReturn(ok());

        // A blank actor is not "null" but must not be attributed — it falls through like null.
        service.run(null, "   ");

        verify(assessmentService).runAssessment(eq("1"), eq(AssessmentTrigger.SCHEDULED), eq("system"), any());
        ArgumentCaptor<NotificationAudit> auditCaptor = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(auditRepo).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getChangedBy()).isEqualTo("system");
    }

    @Test
    void cpLookupReturningNullIsTreatedAsNoRecipient() {
        AppliedCovenant c = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c));
        when(monitoringService.summaryFor("CP-1")).thenReturn(summary("CP-1", 1, 0, 1, 0, 0));
        when(creditProposalClient.lookup("CP-1")).thenReturn(null); // no details → rm/wrm stay null

        RunResult result = service.run(null, "tester");

        verify(notificationClient, never()).send(any());
        assertThat(result.notificationsFailed()).isEqualTo(1);
        ArgumentCaptor<NotificationAudit> auditCaptor = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(auditRepo).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOutcome()).isEqualTo(NotificationAudit.Outcome.FAILED);
        assertThat(auditCaptor.getValue().getCpIds()).isEqualTo("CP-1");
    }

    @Test
    void recipientWithNullOrBlankEmailIsNoRecipient() {
        AppliedCovenant c1 = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        AppliedCovenant c2 = applied("2", "CP-2", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c1, c2));
        when(monitoringService.summaryFor("CP-1")).thenReturn(summary("CP-1", 1, 0, 1, 0, 0));
        when(monitoringService.summaryFor("CP-2")).thenReturn(summary("CP-2", 1, 0, 1, 0, 0));
        // RM present as a user but with an unusable email → still "no recipient".
        when(creditProposalClient.lookup("CP-1"))
                .thenReturn(withUsers("CP-1", new CpUser("rm1", "RM One", null), null));
        when(creditProposalClient.lookup("CP-2"))
                .thenReturn(withUsers("CP-2", new CpUser("rm2", "RM Two", "   "), null));

        RunResult result = service.run(null, "tester");

        verify(notificationClient, never()).send(any());
        assertThat(result.cpsTouched()).isEqualTo(2);
        assertThat(result.notificationsSent()).isZero();
        assertThat(result.notificationsFailed()).isEqualTo(1); // one aggregate no-recipient row
        ArgumentCaptor<NotificationAudit> auditCaptor = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(auditRepo).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getCpIds()).isEqualTo("CP-1,CP-2");
    }

    @Test
    void nonSuccessResultWithoutErrorTextGetsDefaultMessage() {
        AppliedCovenant c = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c));
        when(monitoringService.summaryFor("CP-1")).thenReturn(summary("CP-1", 1, 0, 1, 0, 0));
        when(creditProposalClient.lookup("CP-1")).thenReturn(details("CP-1", "rm@x", "wrm@x"));
        // Failure with no error string → the audit records a generic non-2xx message.
        when(notificationClient.send(any())).thenReturn(new NotificationResult(false, 500, "body", null));

        RunResult result = service.run(null, "tester");

        assertThat(result.notificationsFailed()).isEqualTo(1);
        ArgumentCaptor<NotificationAudit> auditCaptor = ArgumentCaptor.forClass(NotificationAudit.class);
        verify(auditRepo).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOutcome()).isEqualTo(NotificationAudit.Outcome.FAILED);
        assertThat(auditCaptor.getValue().getErrorMessage()).contains("non-2xx");
    }

    @Test
    void assessmentExceptionWithNullMessageIsSanitisedAndBatchContinues() {
        AppliedCovenant c = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c));
        // A null-message exception must be sanitised to a safe token, not NPE the run.
        when(assessmentService.runAssessment(eq("1"), any(), any(), any()))
                .thenThrow(new RuntimeException());

        RunResult result = service.run(null, "tester");

        assertThat(result.covenantsAssessed()).isZero();
        assertThat(result.cpsTouched()).isZero();
        verify(notificationClient, never()).send(any());
        verify(auditRepo, never()).save(any());
    }

    @Test
    void priorAssessmentSuppliesLastAssessedToTimingCalculator() {
        AppliedCovenant c = applied("1", "CP-1", LONG_AGO, AppliedCovenantStatus.ACTIVE);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any())).thenReturn(activePage(c));
        // A prior assessment exists → the batched latest-executedAt map feeds its instant to the calculator.
        when(assessmentRepo.findLatestExecutedAtByAppliedCovenantIdIn(any()))
                .thenReturn(List.of(lastExecuted("1", LONG_AGO.atStartOfDay(ZoneId.systemDefault()).toInstant())));
        when(monitoringService.summaryFor("CP-1")).thenReturn(summary("CP-1", 1, 0, 1, 0, 0));
        when(creditProposalClient.lookup("CP-1")).thenReturn(details("CP-1", "rm@x", "wrm@x"));
        when(notificationClient.send(any())).thenReturn(ok());

        RunResult result = service.run(null, "tester");

        // Last assessed 5y ago on a daily cron → still overdue → assessed again this run.
        assertThat(result.covenantsAssessed()).isEqualTo(1);
        assertThat(result.notificationsSent()).isEqualTo(1);
    }

    private static NotificationResult ok() {
        return new NotificationResult(true, 200, "{\"status\":\"QUEUED\"}", null);
    }

    private static CpMonitoringSummary summary(String cpId, long applied, long compliant,
            long breached, long error, long pending) {
        // dispositioned (US-MON-20) is irrelevant to the breach-notification run → 0.
        return new CpMonitoringSummary(cpId, applied, compliant, breached, error, pending, 0);
    }

    private static CreditProposalDetails details(String cpId, String rmEmail, String wrmEmail) {
        CpUser rm = rmEmail == null ? null : new CpUser("rm-" + cpId, "RM " + cpId, rmEmail);
        CpUser wrm = wrmEmail == null ? null : new CpUser("wrm-" + cpId, "WRM " + cpId, wrmEmail);
        return withUsers(cpId, rm, wrm);
    }

    /** Build CP details with explicit RM/WRM users (e.g. a user carrying a null/blank email). */
    private static CreditProposalDetails withUsers(String cpId, CpUser rm, CpUser wrm) {
        return new CreditProposalDetails(cpId, "Name " + cpId, "SEG", "SECTOR", "APPROVED",
                List.of("tag"), rm, wrm, null, null);
    }

    private static Page<AppliedCovenant> activePage(AppliedCovenant... covenants) {
        return new PageImpl<>(List.of(covenants));
    }

    private static AppliedCovenantLastExecuted lastExecuted(String id, Instant executedAt) {
        return new AppliedCovenantLastExecuted() {
            @Override
            public String getAppliedCovenantId() {
                return id;
            }

            @Override
            public Instant getLastExecutedAt() {
                return executedAt;
            }
        };
    }

    private static AppliedCovenant applied(String id, String cpId, LocalDate startDate,
            AppliedCovenantStatus status) {
        CovenantMaster master = new CovenantMaster();
        setId(master, id + "-m");
        master.setName("M-" + id);
        master.setCategory(CovenantCategory.FINANCIAL);
        master.setConditionExpression("DSCR > 1");
        master.setBusinessSegments(List.of());
        master.setActive(true);

        AppliedCovenant a = new AppliedCovenant();
        setId(a, id);
        a.setCpId(cpId);
        a.setCovenantMaster(master);
        a.setFrequency(Frequency.DAILY);
        a.setCronExpression(Frequency.DAILY.defaultCron());
        a.setStartDate(startDate);
        a.setStatus(status);
        a.setSnapshotJson("{}");
        return a;
    }

    private static void setId(Object target, String id) {
        try {
            Field f = target.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
