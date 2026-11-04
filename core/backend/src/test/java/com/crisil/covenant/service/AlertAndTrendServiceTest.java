package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.dto.AlertView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.ResultTrendPoint;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.enums.DispositionApproval;
import com.crisil.covenant.enums.TimingStatus;
import com.crisil.covenant.repository.AssessmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** B6 alerts and C9 monthly trend, from the same review rows and assessments the screens use. */
@ExtendWith(MockitoExtension.class)
class AlertAndTrendServiceTest {

    @Mock private MonitoringService monitoringService;
    @Mock private ReviewService reviewService;
    @Mock private AssessmentRepository assessmentRepo;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static CpReviewRow row(String id, AssessmentResult result, boolean dispositionable,
                                   DispositionApproval approval, String requestedBy, TimingStatus timing,
                                   boolean atRisk) {
        return new CpReviewRow(id, "m", "Cov " + id, "DSCR >= 1.5", null, AssessmentType.AUTO, "1.6", "NUMBER", null,
                result, "a" + id, null, CovenantDispositionStatus.BREACHED, null, CovenantStatusSource.SYSTEM, null,
                dispositionable, null, null, timing, timing == TimingStatus.OVERDUE ? 3 : 0, List.of(), approval,
                approval == null ? null : CovenantDispositionStatus.WAIVER, requestedBy, atRisk);
    }

    @Test
    void alertsAreSortedApprovalsFirstAndSkipOwnRequests() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "wrm-meera", null, List.of(new SimpleGrantedAuthority("ROLE_APPROVER"))));
        when(monitoringService.ownedSummary("wrm-meera")).thenReturn(List.of(new CpMonitoringSummary("CP-1", 4, 1, 2, 0, 1, 0)));
        when(reviewService.reviewRows("CP-1")).thenReturn(List.of(
                row("1", AssessmentResult.COMPLIANT, false, null, null, TimingStatus.UP_TO_DATE, true),
                row("2", AssessmentResult.BREACHED, true, null, null, TimingStatus.OVERDUE, false),
                row("3", AssessmentResult.BREACHED, true, DispositionApproval.REQUESTED, "rm-anil", null, false),
                row("4", AssessmentResult.BREACHED, true, DispositionApproval.REQUESTED, "wrm-meera", null, false),
                row("5", AssessmentResult.PENDING_REVIEW, false, null, null, null, false)));

        List<AlertView> alerts = new AlertService(monitoringService, reviewService).alertsFor("wrm-meera");

        assertThat(alerts).extracting(AlertView::kind)
                .containsExactly("APPROVAL", "BREACH", "OVERDUE", "REVIEW", "AT_RISK");
        assertThat(alerts.get(0).message()).isEqualTo("rm-anil asks for a waiver on Cov 3");
        assertThat(alerts.get(1).appliedCovenantId()).isEqualTo("2");
        assertThat(alerts.get(2).message()).contains("3 day(s) overdue");
    }

    @Test
    void anRmSeesTheirOwnPendingRequestAsNothingToDo() {
        List<AlertView> out = AlertService.alertsForRow("CP-1",
                row("3", AssessmentResult.BREACHED, true, DispositionApproval.REQUESTED, "rm-anil", null, false),
                "rm-anil", false);
        assertThat(out).isEmpty();
    }

    @Test
    void trendCountsChecksPerMonthIncludingEmptyMonths() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC);
        when(monitoringService.summary("rm-anil")).thenReturn(List.of(new CpMonitoringSummary("CP-1", 2, 1, 1, 0, 0, 0)));
        when(assessmentRepo.findByAppliedCovenantCpIdOrderByScheduledAtDesc("CP-1")).thenReturn(List.of(
                assessment("2026-11-01T06:00:00Z", AssessmentResult.BREACHED),
                assessment("2026-11-02T06:00:00Z", AssessmentResult.COMPLIANT),
                assessment("2026-09-30T06:00:00Z", AssessmentResult.ERROR),
                assessment("2026-09-30T07:00:00Z", AssessmentResult.NA),
                assessment("2025-01-01T06:00:00Z", AssessmentResult.BREACHED),
                assessment(null, AssessmentResult.BREACHED)));

        List<ResultTrendPoint> points = new TrendService(monitoringService, assessmentRepo, clock).monthly("rm-anil", 3);

        assertThat(points).containsExactly(
                new ResultTrendPoint("2026-09", 0, 0, 1, 2),
                new ResultTrendPoint("2026-10", 0, 0, 0, 0),
                new ResultTrendPoint("2026-11", 1, 1, 0, 2));
    }

    private static Assessment assessment(String at, AssessmentResult result) {
        Assessment a = new Assessment();
        a.setExecutedAt(at == null ? null : Instant.parse(at));
        a.setResult(result);
        return a;
    }
}
