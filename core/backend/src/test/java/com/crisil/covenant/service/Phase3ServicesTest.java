package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.BorrowerContactRequest;
import com.crisil.covenant.dto.CertificateRequirementView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.NotificationRequest;
import com.crisil.covenant.dto.NotificationResult;
import com.crisil.covenant.dto.ReminderRunResult;
import com.crisil.covenant.dto.ReportDefinition;
import com.crisil.covenant.dto.SavedReportRequest;
import com.crisil.covenant.entity.BorrowerContact;
import com.crisil.covenant.entity.LetterLog;
import com.crisil.covenant.entity.ReminderLog;
import com.crisil.covenant.entity.SavedReport;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.repository.BorrowerContactRepository;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.LetterLogRepository;
import com.crisil.covenant.repository.NotificationAuditRepository;
import com.crisil.covenant.repository.ReminderLogRepository;
import com.crisil.covenant.repository.SavedReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/** C2 reminders, C11 saved reports and C12 letters. */
@ExtendWith(MockitoExtension.class)
class Phase3ServicesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T06:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @Mock private BorrowerContactRepository contacts;
    @Mock private ReminderLogRepository reminderLog;
    @Mock private CertificateRequirementService requirements;
    @Mock private NotificationClient notificationClient;
    @Mock private NotificationAuditRepository auditRepo;
    @Mock private MonitoringService monitoringService;
    @Mock private ReviewService reviewService;
    @Mock private CreditProposalCacheRepository cacheRepo;
    @Mock private SavedReportRepository reports;
    @Mock private LetterLogRepository letterLog;

    private final ObjectMapper mapper = new ObjectMapper();

    private static CertificateRequirementView requirement(String id, CertificateRequirementView.State state, LocalDate due) {
        return new CertificateRequirementView(id, "CP-1", LocalDate.of(2026, 9, 30), due, null, state,
                state == CertificateRequirementView.State.OVERDUE ? 4 : 0, List.of());
    }

    private static BorrowerContact contact() {
        BorrowerContact c = new BorrowerContact("CP-1");
        c.setContactName("Ms Rao, CFO");
        c.setEmail("cfo@borrower.test");
        c.setDaysBefore(7);
        return c;
    }

    // ---------------------------------------------------------------- C2

    private ReminderService reminders() {
        return new ReminderService(contacts, reminderLog, requirements, notificationClient, auditRepo, mapper, CLOCK);
    }

    @Test
    void remindsOnceBeforeTheDueDateAndWeeklyWhileOverdue() {
        BorrowerContact c = contact();
        when(contacts.findByRemindersOnTrue()).thenReturn(List.of(c));
        when(requirements.forCp("CP-1")).thenReturn(List.of(
                requirement("soon", CertificateRequirementView.State.PENDING, TODAY.plusDays(5)),
                requirement("later", CertificateRequirementView.State.PENDING, TODAY.plusDays(20)),
                requirement("late", CertificateRequirementView.State.OVERDUE, TODAY.minusDays(4)),
                requirement("got", CertificateRequirementView.State.RECEIVED, TODAY.minusDays(1))));
        when(reminderLog.existsByRequirementIdAndKind("soon", ReminderLog.Kind.DUE_SOON)).thenReturn(false);
        when(reminderLog.existsByRequirementIdAndKindAndSentOnAfter("late", ReminderLog.Kind.OVERDUE, TODAY.minusDays(7)))
                .thenReturn(false);
        when(notificationClient.send(any())).thenReturn(new NotificationResult(true, 200, "{}", null));

        ReminderRunResult result = reminders().run("system");

        assertThat(result).isEqualTo(new ReminderRunResult(1, 2, 0));
        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationClient, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(r -> {
            assertThat(r.emailTo()).isEqualTo("cfo@borrower.test");
            assertThat(r.templateCode()).isEqualTo(ReminderService.TEMPLATE);
        });
        assertThat(sent.getAllValues().get(1).priority()).isEqualTo("HIGH");
        ArgumentCaptor<ReminderLog> logged = ArgumentCaptor.forClass(ReminderLog.class);
        verify(reminderLog, times(2)).save(logged.capture());
        assertThat(logged.getAllValues()).extracting(ReminderLog::getKind)
                .containsExactly(ReminderLog.Kind.DUE_SOON, ReminderLog.Kind.OVERDUE);
        verify(auditRepo, times(2)).save(any());
    }

    @Test
    void nothingIsSentTwiceAndAFailedSendIsLogged() {
        BorrowerContact c = contact();
        when(contacts.findByRemindersOnTrue()).thenReturn(List.of(c));
        when(requirements.forCp("CP-1")).thenReturn(List.of(
                requirement("soon", CertificateRequirementView.State.PENDING, TODAY.plusDays(5)),
                requirement("late", CertificateRequirementView.State.OVERDUE, TODAY.minusDays(4))));
        when(reminderLog.existsByRequirementIdAndKind("soon", ReminderLog.Kind.DUE_SOON)).thenReturn(true);
        when(reminderLog.existsByRequirementIdAndKindAndSentOnAfter(eq("late"), eq(ReminderLog.Kind.OVERDUE), any()))
                .thenReturn(false);
        when(notificationClient.send(any())).thenReturn(new NotificationResult(false, 503, null, "down"));

        ReminderRunResult result = reminders().run("system");

        assertThat(result).isEqualTo(new ReminderRunResult(1, 0, 1));
        ArgumentCaptor<ReminderLog> logged = ArgumentCaptor.forClass(ReminderLog.class);
        verify(reminderLog).save(logged.capture());
        assertThat(logged.getValue().getOutcome()).isEqualTo("FAILED");
        assertThat(logged.getValue().getDetail()).isEqualTo("down");
    }

    @Test
    void savesTheContactTrimmed() {
        when(contacts.findById("CP-1")).thenReturn(Optional.empty());
        when(contacts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        BorrowerContact c = reminders().saveContact("CP-1",
                new BorrowerContactRequest(" Ms Rao ", " cfo@borrower.test ", true, 10));
        assertThat(c.getContactName()).isEqualTo("Ms Rao");
        assertThat(c.getEmail()).isEqualTo("cfo@borrower.test");
        assertThat(c.getDaysBefore()).isEqualTo(10);
        assertThat(c.getUpdatedBy()).isEqualTo("system");
    }

    // ---------------------------------------------------------------- C11

    private ScheduledReportService reportService() {
        return new ScheduledReportService(monitoringService, reviewService, cacheRepo, reports, notificationClient,
                auditRepo, mapper, CLOCK);
    }

    private static CpReviewRow row(String id, AssessmentResult result, boolean atRisk) {
        return new CpReviewRow(id, "m", "Cov " + id, "DSCR >= 1.5", null, AssessmentType.AUTO, "1.55", "NUMBER",
                new BigDecimal("0.05"), result, "a", null, CovenantDispositionStatus.COMPLIED, null,
                CovenantStatusSource.SYSTEM, null, false, null, LocalDate.of(2026, 12, 31), null, 0, List.of(),
                null, null, null, atRisk);
    }

    @Test
    void aReportFiltersRowsAndKeepsTheChosenColumnsInOrder() {
        when(monitoringService.visibleCpIds("rm-anil")).thenReturn(List.of("CP-1001", "CP-2001"));
        when(cacheRepo.findAllById(any())).thenReturn(List.of());
        when(reviewService.reviewRows("CP-1001")).thenReturn(List.of(
                row("1", AssessmentResult.BREACHED, false), row("2", AssessmentResult.COMPLIANT, true),
                row("3", AssessmentResult.COMPLIANT, false)));

        List<Map<String, String>> rows = reportService().run("rm-anil", new ReportDefinition(
                List.of("result", "cpId", "nope", "covenantName"), List.of("BREACHED", "AT_RISK"), null, "1001"));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).keySet()).containsExactly("cpId", "covenantName", "result");
        assertThat(rows.get(1)).containsEntry("covenantName", "Cov 2");
        verify(reviewService, never()).reviewRows("CP-2001");
        assertThat(new ReportDefinition(null, null, null, null).chosenColumns()).isEqualTo(ReportDefinition.COLUMNS);
    }

    @Test
    void schedulesAreDueDailyWeeklyAndMonthly() {
        assertThat(ScheduledReportService.isDue(SavedReport.Schedule.NONE, null, TODAY)).isFalse();
        assertThat(ScheduledReportService.isDue(SavedReport.Schedule.DAILY, null, TODAY)).isTrue();
        assertThat(ScheduledReportService.isDue(SavedReport.Schedule.DAILY, TODAY, TODAY)).isFalse();
        assertThat(ScheduledReportService.isDue(SavedReport.Schedule.WEEKLY, TODAY.minusDays(6), TODAY)).isFalse();
        assertThat(ScheduledReportService.isDue(SavedReport.Schedule.WEEKLY, TODAY.minusDays(7), TODAY)).isTrue();
        assertThat(ScheduledReportService.isDue(SavedReport.Schedule.MONTHLY, TODAY.minusMonths(1), TODAY)).isTrue();
        assertThat(ScheduledReportService.isDue(SavedReport.Schedule.MONTHLY, TODAY.minusDays(20), TODAY)).isFalse();
    }

    @Test
    void savingChecksTheScheduleNeedsAnAddressAndOnlyTheOwnerSeesIt() {
        lenient().when(reports.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ReportDefinition def = new ReportDefinition(List.of(), List.of(), null, null);
        assertThatThrownBy(() -> reportService().create("rm-anil",
                new SavedReportRequest("x", def, SavedReport.Schedule.WEEKLY, " ")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("e-mail");
        assertThatThrownBy(() -> reportService().create("rm-anil",
                new SavedReportRequest("x", def, SavedReport.Schedule.WEEKLY, "a@b.test; c@d.test")))
                .hasMessageContaining("commas");
        var saved = reportService().create("rm-anil",
                new SavedReportRequest(" Weekly breaches ", def, SavedReport.Schedule.WEEKLY, "a@b.test, c@d.test"));
        assertThat(saved.name()).isEqualTo("Weekly breaches");
        assertThat(saved.schedule()).isEqualTo("WEEKLY");

        SavedReport other = new SavedReport("wrm-meera", Instant.now(CLOCK));
        when(reports.findById(other.getId())).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> reportService().owned("rm-anil", other.getId())).hasMessageContaining("not found");
    }

    @Test
    void dueReportsAreEmailedAndMarkedSent() throws Exception {
        SavedReport r = new SavedReport("rm-anil", Instant.now(CLOCK));
        r.setName("Breaches");
        r.setDefinitionJson(mapper.writeValueAsString(new ReportDefinition(List.of("cpId"), List.of(), null, null)));
        r.setSchedule(SavedReport.Schedule.DAILY);
        r.setEmailTo("head@bank.test");
        when(reports.findByScheduleNot(SavedReport.Schedule.NONE)).thenReturn(List.of(r));
        when(monitoringService.visibleCpIds("rm-anil")).thenReturn(List.of("CP-1"));
        when(cacheRepo.findAllById(any())).thenReturn(List.of());
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(row("1", AssessmentResult.COMPLIANT, false)));
        when(notificationClient.send(any())).thenReturn(new NotificationResult(true, 200, "{}", null));

        assertThat(reportService().sendDue("system")).isEqualTo(1);
        assertThat(r.getLastSentOn()).isEqualTo(TODAY);
        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationClient).send(sent.capture());
        assertThat(sent.getValue().variables().cpMonitoring()).hasSize(2);
        assertThat(reportService().sendDue("system")).isZero();
        assertThat(reportService().xlsx("A very long report name over thirty-one characters", List.of("cpId"),
                List.of(Map.of("cpId", "CP-1")))).isNotEmpty();
    }

    // ---------------------------------------------------------------- C12

    private LetterService letters() {
        return new LetterService(reviewService, contacts, letterLog, CLOCK);
    }

    private static String documentXml(byte[] docx) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals("word/document.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }

    @Test
    void aBreachNoticeIsFilledInAndLogged() throws Exception {
        CpReviewRow breached = new CpReviewRow("ac-1", "m", "Minimum DSCR", "DSCR >= 1.50", null, AssessmentType.AUTO,
                "1.20", "NUMBER", null, AssessmentResult.BREACHED, "a1", Instant.parse("2026-11-01T06:00:00Z"),
                CovenantDispositionStatus.BREACHED, null, CovenantStatusSource.SYSTEM, null, true, null, null, null, 0,
                List.of());
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(breached));
        when(contacts.findById("CP-1")).thenReturn(Optional.of(contact()));

        String xml = documentXml(letters().letter("CP-1", "ac-1", LetterService.Kind.BREACH_NOTICE, "rm-anil"));

        assertThat(xml).contains("Notice of covenant breach", "Ms Rao, CFO", "DSCR &gt;= 1.50", "1.20",
                "checked on 1 November 2026", "rm-anil");
        ArgumentCaptor<LetterLog> logged = ArgumentCaptor.forClass(LetterLog.class);
        verify(letterLog).save(logged.capture());
        assertThat(logged.getValue().getKind()).isEqualTo("BREACH_NOTICE");
        assertThatThrownBy(() -> letters().letter("CP-1", "ac-1", LetterService.Kind.WAIVER_GRANTED, "rm-anil"))
                .hasMessageContaining("No approved waiver");
        assertThatThrownBy(() -> letters().letter("CP-1", "nope", LetterService.Kind.BREACH_NOTICE, "rm-anil"))
                .hasMessageContaining("not found");
    }

    @Test
    void anExtensionLetterNamesTheDateAndReason() throws Exception {
        CpReviewRow extended = new CpReviewRow("ac-1", "m", "Minimum DSCR", "DSCR >= 1.50", null, AssessmentType.AUTO,
                "1.20", "NUMBER", null, AssessmentResult.BREACHED, "a1", null, CovenantDispositionStatus.EXTENSION,
                LocalDate.of(2027, 1, 31), CovenantStatusSource.USER, "refinancing under way", false, null, null, null,
                0, List.of());
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(extended));
        when(contacts.findById(anyString())).thenReturn(Optional.empty());

        String xml = documentXml(letters().letter("CP-1", "ac-1", LetterService.Kind.EXTENSION_GRANTED, null));

        assertThat(xml).contains("Extension of time to comply", "31 January 2027", "refinancing under way",
                "The Borrower");
    }
}
