package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.controller.BorrowerReminderController;
import com.crisil.covenant.controller.SavedReportController;
import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.dto.CertificateRequirementView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.ReportDefinition;
import com.crisil.covenant.dto.SavedReportRequest;
import com.crisil.covenant.dto.UpdateWorkTaskRequest;
import com.crisil.covenant.entity.BorrowerContact;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.entity.ReminderLog;
import com.crisil.covenant.entity.SavedReport;
import com.crisil.covenant.entity.WorkTask;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.enums.DispositionApproval;
import com.crisil.covenant.enums.TimingStatus;
import com.crisil.covenant.repository.BorrowerContactRepository;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.LetterLogRepository;
import com.crisil.covenant.repository.NotificationAuditRepository;
import com.crisil.covenant.repository.ReminderLogRepository;
import com.crisil.covenant.repository.SavedReportRepository;
import com.crisil.covenant.repository.WorkTaskRepository;
import com.crisil.covenant.security.CpAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The less common paths of the Phase 3 services and their controllers. */
@ExtendWith(MockitoExtension.class)
class Phase3EdgeCasesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T06:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @Mock private MonitoringService monitoringService;
    @Mock private ReviewService reviewService;
    @Mock private CreditProposalCacheRepository cacheRepo;
    @Mock private SavedReportRepository reports;
    @Mock private NotificationClient notificationClient;
    @Mock private NotificationAuditRepository auditRepo;
    @Mock private BorrowerContactRepository contacts;
    @Mock private ReminderLogRepository reminderLog;
    @Mock private CertificateRequirementService requirements;
    @Mock private LetterLogRepository letterLog;
    @Mock private WorkTaskRepository taskRepo;
    @Mock private CpAccessGuard guard;

    private final ObjectMapper mapper = new ObjectMapper();

    private ScheduledReportService reports() {
        return new ScheduledReportService(monitoringService, reviewService, cacheRepo, reports, notificationClient,
                auditRepo, mapper, CLOCK);
    }

    private static CpReviewRow row(AssessmentResult result, CovenantDispositionStatus status, LocalDate extension,
                                   String note, String value, Instant assessedAt) {
        return new CpReviewRow("ac-1", "m", "Minimum DSCR", null, null, AssessmentType.AUTO, value, null, null,
                result, "a1", assessedAt, status, extension, CovenantStatusSource.USER, note, false, null, null,
                TimingStatus.OVERDUE, 2, List.of(), DispositionApproval.APPROVED, status, "rm-anil", false);
    }

    private static Object set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
        return target;
    }

    // ---------------------------------------------------------------- reports

    @Test
    void reportHelpersHandleEveryCase() {
        assertThat(ScheduledReportService.validAddressList("a@b.test, c@d.test")).isTrue();
        assertThat(ScheduledReportService.validAddressList("a@b.test,")).isFalse();
        assertThat(ScheduledReportService.validAddressList("@b.test")).isFalse();
        assertThat(ScheduledReportService.validAddressList("a@")).isFalse();
        assertThat(ScheduledReportService.validAddressList("a@@b")).isFalse();
        assertThat(ScheduledReportService.validAddressList("a b@c.test")).isFalse();
        assertThat(ScheduledReportService.validAddressList("x".repeat(250) + "@b.test")).isFalse();
        assertThat(ScheduledReportService.containsIgnoreCase("CP-1001", "")).isTrue();
        assertThat(ScheduledReportService.containsIgnoreCase("CP-1001", "cp-10")).isTrue();
        assertThat(ScheduledReportService.containsIgnoreCase("CP-1001", "2001")).isFalse();
        assertThat(ScheduledReportService.containsIgnoreCase("CP", "CP-1001")).isFalse();
        assertThat(ScheduledReportService.sameIgnoringCase("CORPORATE", "corporate")).isTrue();
        assertThat(ScheduledReportService.sameIgnoringCase("CORPORATE", "CORP")).isFalse();
        assertThat(ScheduledReportService.sameIgnoringCase("CORPORATE", null)).isFalse();
        assertThat(ScheduledReportService.cut(null, 3)).isNull();
        assertThat(ScheduledReportService.cut("abcd", 3)).isEqualTo("abc");
        Map<String, String> cells = ScheduledReportService.cells("CP-1", null,
                row(null, null, null, null, null, null), ReportDefinition.COLUMNS);
        assertThat(cells).containsEntry("result", "NOT ASSESSED").containsEntry("segment", "")
                .containsEntry("headroom", "").containsEntry("overdueDays", "2");
    }

    @Test
    void aReportCanFilterBySegment() throws Exception {
        when(monitoringService.summary("rm-anil")).thenReturn(List.of(
                new CpMonitoringSummary("CP-1", 1, 1, 0, 0, 0, 0), new CpMonitoringSummary("CP-2", 1, 1, 0, 0, 0, 0)));
        CreditProposalCacheEntry corp = (CreditProposalCacheEntry) set(set(new CreditProposalCacheEntry(), "cpId", "CP-1"),
                "businessSegment", "CORPORATE");
        when(cacheRepo.findAllById(any())).thenReturn(List.of(corp));
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(row(AssessmentResult.COMPLIANT, null, null, null, "1.6", null)));

        List<Map<String, String>> rows = reports().run("rm-anil",
                new ReportDefinition(List.of("cpId", "segment"), null, " corporate ", null));

        assertThat(rows).containsExactly(Map.of("cpId", "CP-1", "segment", "CORPORATE"));
        verify(reviewService, never()).reviewRows("CP-2");
    }

    @Test
    void savedReportsCanBeChangedDeletedAndReadBack() throws Exception {
        SavedReport mine = new SavedReport("rm-anil", Instant.now(CLOCK));
        mine.setDefinitionJson("not json");
        lenient().when(reports.findById(mine.getId())).thenReturn(Optional.of(mine));
        lenient().when(reports.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reports.findByOwnerOrderByNameAsc("rm-anil")).thenReturn(List.of(mine));

        assertThat(reports().definition(mine).chosenColumns()).isEqualTo(ReportDefinition.COLUMNS);
        var updated = reports().update("rm-anil", mine.getId(), new SavedReportRequest("Renamed",
                new ReportDefinition(List.of("cpId"), List.of(), null, null), SavedReport.Schedule.NONE, null));
        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.emailTo()).isNull();
        assertThat(reports().list("rm-anil")).hasSize(1);
        reports().delete("rm-anil", mine.getId());
        verify(reports).delete(mine);
        when(reports.findById("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reports().owned("rm-anil", "gone")).hasMessageContaining("not found");
    }

    @Test
    void aFailedOrUnaddressedScheduledReportIsNotMarkedSent() throws Exception {
        SavedReport failing = new SavedReport("rm-anil", Instant.now(CLOCK));
        failing.setName("Breaches");
        failing.setDefinitionJson(mapper.writeValueAsString(new ReportDefinition(List.of(), List.of(), null, null)));
        failing.setSchedule(SavedReport.Schedule.WEEKLY);
        failing.setEmailTo("head@bank.test");
        SavedReport noAddress = new SavedReport("rm-anil", Instant.now(CLOCK));
        noAddress.setSchedule(SavedReport.Schedule.DAILY);
        SavedReport notDue = new SavedReport("rm-anil", Instant.now(CLOCK));
        notDue.setSchedule(SavedReport.Schedule.MONTHLY);
        notDue.setEmailTo("x@y.test");
        notDue.setLastSentOn(TODAY.minusDays(3));
        when(reports.findByScheduleNot(SavedReport.Schedule.NONE)).thenReturn(List.of(failing, noAddress, notDue));
        when(monitoringService.summary("rm-anil")).thenReturn(List.of());
        when(cacheRepo.findAllById(any())).thenReturn(List.of());
        when(notificationClient.send(any())).thenReturn(null);

        assertThat(reports().sendDue("system")).isZero();
        assertThat(failing.getLastSentOn()).isNull();
        verify(auditRepo).save(any());
    }

    // ---------------------------------------------------------------- reminders

    @Test
    void reminderTimingCoversEveryState() {
        ReminderService service = new ReminderService(contacts, reminderLog, requirements, notificationClient, auditRepo,
                mapper, CLOCK);
        BorrowerContact c = new BorrowerContact("CP-1");
        c.setDaysBefore(7);
        CertificateRequirementView farAway = new CertificateRequirementView("q1", "CP-1", TODAY, TODAY.plusDays(30), null,
                CertificateRequirementView.State.PENDING, 0, List.of());
        CertificateRequirementView noDate = new CertificateRequirementView("q2", "CP-1", TODAY, null, null,
                CertificateRequirementView.State.PENDING, 0, List.of());
        CertificateRequirementView passed = new CertificateRequirementView("q3", "CP-1", TODAY, TODAY.minusDays(1), null,
                CertificateRequirementView.State.PENDING, 0, List.of());
        CertificateRequirementView received = new CertificateRequirementView("q4", "CP-1", TODAY, TODAY, null,
                CertificateRequirementView.State.RECEIVED, 0, List.of());
        CertificateRequirementView lateButChased = new CertificateRequirementView("q5", "CP-1", TODAY, TODAY.minusDays(3),
                null, CertificateRequirementView.State.OVERDUE, 3, List.of());
        when(reminderLog.existsByRequirementIdAndKindAndSentOnAfter("q5", ReminderLog.Kind.OVERDUE, TODAY.minusDays(7)))
                .thenReturn(true);

        assertThat(service.due(farAway, c, TODAY)).isEmpty();
        assertThat(service.due(noDate, c, TODAY)).isEmpty();
        assertThat(service.due(passed, c, TODAY)).isEmpty();
        assertThat(service.due(received, c, TODAY)).isEmpty();
        assertThat(service.due(lateButChased, c, TODAY)).isEmpty();
        when(contacts.findById("CP-1")).thenReturn(Optional.of(c));
        assertThat(service.contact("CP-1")).contains(c);
        when(reminderLog.findByCpIdOrderByCreatedAtDesc("CP-1")).thenReturn(List.of());
        assertThat(service.logFor("CP-1")).isEmpty();
    }

    @Test
    void controllersCheckAccessAndAnswer() {
        ReminderService reminders = new ReminderService(contacts, reminderLog, requirements, notificationClient, auditRepo,
                mapper, CLOCK);
        BorrowerReminderController ctl = new BorrowerReminderController(reminders, guard);
        when(contacts.findById("CP-1")).thenReturn(Optional.empty());
        assertThat(ctl.contact("CP-1").getStatusCode().value()).isEqualTo(204);
        when(contacts.findById("CP-2")).thenReturn(Optional.of(new BorrowerContact("CP-2")));
        assertThat(ctl.contact("CP-2").getStatusCode().value()).isEqualTo(200);
        when(reminderLog.findByCpIdOrderByCreatedAtDesc("CP-1")).thenReturn(List.of());
        assertThat(ctl.reminders("CP-1")).isEmpty();
        when(contacts.findByRemindersOnTrue()).thenReturn(List.of());
        assertThat(ctl.run().sent()).isZero();
        verify(guard, org.mockito.Mockito.times(2)).requireCp("CP-1");

        SavedReportController reportsCtl = new SavedReportController(reports());
        when(reports.findByScheduleNot(SavedReport.Schedule.NONE)).thenReturn(List.of());
        assertThat(reportsCtl.scheduledRun()).containsEntry("sent", 0);
        when(monitoringService.summary(any())).thenReturn(List.of());
        assertThat(reportsCtl.run(new ReportDefinition(null, null, null, null))).isEmpty();
    }

    // ---------------------------------------------------------------- letters

    private static String xml(byte[] docx) throws Exception {
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
    void waiverAndExtensionLettersCoverTheirOptionalParts() throws Exception {
        LetterService letters = new LetterService(reviewService, contacts, letterLog, CLOCK);
        when(contacts.findById("CP-1")).thenReturn(Optional.empty());

        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(
                row(AssessmentResult.BREACHED, CovenantDispositionStatus.WAIVER, null, "  one-off  ", null, null)));
        String waiver = xml(letters.letter("CP-1", "ac-1", LetterService.Kind.WAIVER_GRANTED, "rm-anil"));
        assertThat(waiver).contains("Waiver of covenant breach", "Reason recorded: one-off.")
                .doesNotContain("Latest figure");
        assertThatThrownBy(() -> letters.letter("CP-1", "ac-1", LetterService.Kind.EXTENSION_GRANTED, "rm-anil"))
                .hasMessageContaining("No approved extension");

        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(
                row(AssessmentResult.BREACHED, CovenantDispositionStatus.EXTENSION, null, " ", "1.2", null)));
        assertThat(xml(letters.letter("CP-1", "ac-1", LetterService.Kind.EXTENSION_GRANTED, null)))
                .contains("the agreed date").doesNotContain("Reason recorded");

        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(
                row(AssessmentResult.COMPLIANT, CovenantDispositionStatus.COMPLIED, null, null, "1.6", null)));
        assertThatThrownBy(() -> letters.letter("CP-1", "ac-1", LetterService.Kind.BREACH_NOTICE, "rm-anil"))
                .hasMessageContaining("not breached");
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(
                row(AssessmentResult.BREACHED, CovenantDispositionStatus.BREACHED, null, null, "1.2", null)));
        assertThat(xml(letters.letter("CP-1", "ac-1", LetterService.Kind.BREACH_NOTICE, "rm-anil")))
                .contains("was not met.");
    }

    // ---------------------------------------------------------------- work queue

    @Test
    void workQueueHandlesCpsWithoutOwnersAndOtherPeoplesTasks() throws Exception {
        WorkQueueService service = new WorkQueueService(monitoringService, reviewService, requirements, cacheRepo,
                taskRepo, org.mockito.Mockito.mock(com.crisil.covenant.repository.AppliedCovenantRepository.class),
                new TaskSyncTracker(java.time.Duration.ofMinutes(2)), CLOCK);
        List<WorkTask> stored = new ArrayList<>();
        when(monitoringService.summary("rm-anil")).thenReturn(List.of(new CpMonitoringSummary("CP-1", 1, 0, 1, 0, 0, 0)));
        when(cacheRepo.findAllById(any())).thenReturn(List.of());
        when(requirements.forCp("CP-1")).thenReturn(List.of());
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(new CpReviewRow("ac-1", "m", "Cov", "x", null,
                AssessmentType.AUTO, null, null, null, AssessmentResult.BREACHED, "a", null,
                CovenantDispositionStatus.BREACHED, null, CovenantStatusSource.SYSTEM, null, true, null, null, null, 0,
                List.of())));
        when(taskRepo.findByCpIdAndStatus("CP-1", WorkTask.Status.OPEN)).thenReturn(List.of());
        WorkTask someoneElses = new WorkTask("CP-1", "x", WorkTask.Kind.REVIEW, "t", "rm-priya", TODAY.plusDays(3),
                Instant.now(CLOCK));
        stored.add(someoneElses);
        when(taskRepo.findByCpIdInOrderByDueDateAsc(anyCollection())).thenReturn(stored);

        assertThat(service.tasksFor("rm-anil", true, true)).isEmpty();
        verify(taskRepo, never()).save(any());

        when(taskRepo.findById(someoneElses.getId())).thenReturn(Optional.of(someoneElses));
        when(taskRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var v = service.update(someoneElses.getId(), new UpdateWorkTaskRequest(" ", null, null, false), "rm-anil");
        assertThat(v.assignee()).isEqualTo("rm-priya");
        assertThat(v.status()).isEqualTo("OPEN");
    }
}
