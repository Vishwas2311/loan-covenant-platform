package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.dto.BreachReportRow;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.enums.DispositionApproval;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.repository.AssessmentRepository.AssessmentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BreachReportServiceTest {

    private static final Clock TODAY = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);

    @Mock private MonitoringService monitoringService;
    @Mock private ReviewService reviewService;
    @Mock private AssessmentRepository assessmentRepo;

    private BreachReportService service() {
        return new BreachReportService(monitoringService, reviewService, assessmentRepo, TODAY);
    }

    private static CpReviewRow row(String id, AssessmentResult result, CovenantDispositionStatus status,
                                   DispositionApproval approval) {
        return new CpReviewRow(id, "m", "Cov-" + id, "DSCR >= 1", null, null, "1.1", "Double", null, result, "a",
                null, status, null, CovenantStatusSource.SYSTEM, null, true, null, null, null, 0, List.of(),
                approval, null, null);
    }

    private record Outcome(String getAppliedCovenantId, AssessmentResult getResult, Instant getScheduledAt,
                           Instant getExecutedAt) implements AssessmentOutcome { }

    private static AssessmentOutcome a(String id, AssessmentResult result, String executed, String scheduled) {
        return new Outcome(id, result, scheduled == null ? null : Instant.parse(scheduled),
                executed == null ? null : Instant.parse(executed));
    }

    @Test
    void listsOpenBreachesWithAgeAndEscalation() {
        when(monitoringService.summary("rm")).thenReturn(List.of(
                new CpMonitoringSummary("CP-1", 4, 1, 2, 1, 0, 1),
                new CpMonitoringSummary("CP-2", 2, 2, 0, 0, 0, 0)));
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(
                row("old", AssessmentResult.BREACHED, CovenantDispositionStatus.BREACHED,
                        DispositionApproval.REQUESTED),
                row("waived", AssessmentResult.BREACHED, CovenantDispositionStatus.WAIVER, DispositionApproval.APPROVED),
                row("err", AssessmentResult.ERROR, CovenantDispositionStatus.COMPLIED, null),
                row("ok", AssessmentResult.COMPLIANT, CovenantDispositionStatus.COMPLIED, null)));
        when(assessmentRepo.findOutcomesByAppliedCovenantIdIn(List.of("old", "waived", "err"))).thenReturn(List.of(
                a("old", AssessmentResult.BREACHED, "2026-09-30T10:00:00Z", null),
                a("old", AssessmentResult.BREACHED, null, "2026-06-30T10:00:00Z"),
                a("old", AssessmentResult.BREACHED, null, null),
                a("old", AssessmentResult.COMPLIANT, "2026-03-31T10:00:00Z", null),
                a("waived", AssessmentResult.BREACHED, "2026-06-30T10:00:00Z", null),
                a("err", AssessmentResult.ERROR, "2026-10-30T10:00:00Z", null)));

        List<BreachReportRow> out = service().openBreaches("rm", 14);

        assertThat(out).extracting(BreachReportRow::appliedCovenantId).containsExactly("old", "waived", "err");
        assertThat(out.get(0).daysOpen()).isEqualTo(125);
        assertThat(out.get(0).escalate()).isTrue();
        assertThat(out.get(1).escalate()).isFalse(); // handled by an approved waiver
        assertThat(out.get(2).daysOpen()).isEqualTo(3);
        assertThat(out.get(2).escalate()).isFalse();
        verify(reviewService, never()).reviewRows("CP-2");

        assertThat(service().openBreachesXlsx("rm", 14)).isNotEmpty();
        assertThat(new String(service().openBreachesPdf("rm", 14), java.nio.charset.StandardCharsets.US_ASCII))
                .contains("Cov-old").contains("3 open item\\(s\\); 1 to escalate.");
    }

    @Test
    void historyKeepsTheNewestOutcomesPerCovenantAndBatchesLargeLists() {
        java.util.List<String> ids = java.util.stream.IntStream.range(0, 1200).mapToObj(i -> "ac-" + i).toList();
        when(assessmentRepo.findOutcomesByAppliedCovenantIdIn(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(java.util.stream.IntStream.range(0, 70)
                        .mapToObj(i -> a("ac-0", AssessmentResult.BREACHED, null, null)).toList());
        java.util.Map<String, List<AssessmentOutcome>> h = service().history(ids);
        assertThat(h.get("ac-0")).hasSize(BreachReportService.HISTORY_DEPTH);
        verify(assessmentRepo, org.mockito.Mockito.times(3)).findOutcomesByAppliedCovenantIdIn(
                org.mockito.ArgumentMatchers.anyList());
    }
}
