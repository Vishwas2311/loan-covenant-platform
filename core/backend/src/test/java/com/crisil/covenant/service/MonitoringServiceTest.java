package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.CovenantDispositionRepository;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.entity.CovenantDisposition;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.dto.ReportingSettingsResponse;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.MonitoringSchedule;
import com.crisil.covenant.repository.MonitoringScheduleRepository;
import com.crisil.covenant.enums.ScheduleStatus;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.crisil.covenant.domain.CovenantOnCp;
import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.domain.MonitoringEvent;
import com.crisil.covenant.enums.TimingStatus;

@ExtendWith(MockitoExtension.class)
class MonitoringServiceTest {

    /** The impersonated RM whose portfolio scopes every monitoring read in these tests. */
    private static final String USER = "rm-anil";

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private AssessmentRepository assessmentRepo;
    @Mock private MonitoringScheduleRepository scheduleRepo;
    @Mock private ReportingSettingsService reportingSettingsService;
    @Mock private CreditProposalCacheRepository cacheRepo;
    @Mock private CovenantDispositionRepository dispositionRepo;
    @InjectMocks private MonitoringService service;

    private record Outcome(String getAppliedCovenantId, String getId, AssessmentResult getResult)
            implements AssessmentRepository.LatestOutcome { }

    @BeforeEach
    void stubSettings() {
        lenient().when(reportingSettingsService.current())
                .thenReturn(ReportingSettingsResponse.defaults());
        // The summary reads every covenant's latest assessment in one batch; answer it from the
        // per-covenant stubs each test sets up, so the tests state their data once.
        java.util.Map<String, Assessment> loaded = new java.util.HashMap<>();
        lenient().when(assessmentRepo.findLatestOutcomes(anyCollection())).thenAnswer(inv -> {
            List<AssessmentRepository.LatestOutcome> out = new java.util.ArrayList<>();
            for (Object id : (java.util.Collection<?>) inv.getArgument(0)) {
                Page<Assessment> page = assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(
                        (String) id, PageRequest.of(0, 1));
                if (page != null) {
                    page.stream().findFirst().ifPresent(a -> {
                        String key = a.getId() != null ? a.getId() : "latest-of-" + id;
                        org.springframework.test.util.ReflectionTestUtils.setField(a, "id", key);
                        loaded.put(key, a);
                        out.add(new Outcome((String) id, key, a.getResult()));
                    });
                }
            }
            return out;
        });
        lenient().when(assessmentRepo.findAllById(any())).thenAnswer(inv -> {
            List<Assessment> out = new java.util.ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                if (loaded.containsKey(id)) {
                    out.add(loaded.get(id));
                }
            }
            return out;
        });
    }

    /** Stub {@link USER}'s portfolio to be exactly the CPs of {@code applied} (the cross-CP read path). */
    private void ownPortfolio(List<AppliedCovenant> applied) {
        List<String> cpIds = applied.stream().map(AppliedCovenant::getCpId).distinct().toList();
        when(cacheRepo.findCpIdsOwnedBy(USER)).thenReturn(cpIds);
        when(appliedRepo.findByCpIdInOrderByIdAsc(anyCollection())).thenReturn(applied);
    }

    @Test
    void summaryAggregatesByCp() {
        AppliedCovenant a1 = newApplied("1", "CP-1");
        AppliedCovenant a2 = newApplied("2", "CP-1");
        AppliedCovenant a3 = newApplied("3", "CP-2");
        ownPortfolio(List.of(a1, a2, a3));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.COMPLIANT, a1));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("2"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.BREACHED, a2));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("3"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        List<CpMonitoringSummary> summary = service.summary(USER);

        assertThat(summary).hasSize(2);
        CpMonitoringSummary cp1 = summary.stream().filter(s -> s.cpId().equals("CP-1")).findFirst().get();
        assertThat(cp1.applied()).isEqualTo(2);
        assertThat(cp1.compliant()).isEqualTo(1);
        assertThat(cp1.breached()).isEqualTo(1);

        CpMonitoringSummary cp2 = summary.stream().filter(s -> s.cpId().equals("CP-2")).findFirst().get();
        assertThat(cp2.applied()).isEqualTo(1);
        assertThat(cp2.pendingReview()).isEqualTo(1);
    }

    @Test
    void summaryHandlesErrorAndPendingReviewLatest() {
        AppliedCovenant a = newApplied("1", "CP-E");
        ownPortfolio(List.of(a));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.ERROR, a));

        CpMonitoringSummary s = service.summary(USER).get(0);
        assertThat(s.error()).isEqualTo(1);
    }

    @Test
    void summaryCountsPendingReviewAndIgnoresNa() {
        AppliedCovenant pending = newApplied("1", "CP-P");
        AppliedCovenant na = newApplied("2", "CP-NA");
        ownPortfolio(List.of(pending, na));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.PENDING_REVIEW, pending));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("2"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.NA, na));

        List<CpMonitoringSummary> summary = service.summary(USER);
        CpMonitoringSummary p = summary.stream().filter(s -> s.cpId().equals("CP-P")).findFirst().get();
        assertThat(p.pendingReview()).isEqualTo(1);
        // NA is informational only — counted in the total, not in any compliance bucket.
        CpMonitoringSummary naCp = summary.stream().filter(s -> s.cpId().equals("CP-NA")).findFirst().get();
        assertThat(naCp.applied()).isEqualTo(1);
        assertThat(naCp.pendingReview()).isZero();
        assertThat(naCp.compliant()).isZero();
    }

    @Test
    void summaryFailsClosedForBlankUser() {
        // US-MON-15: an unidentified caller (no X-User) sees nothing rather than everything.
        assertThat(service.summary(null)).isEmpty();
        assertThat(service.summary("  ")).isEmpty();
        verify(cacheRepo, never()).findCpIdsOwnedBy(any());
        verify(appliedRepo, never()).findByCpIdInOrderByIdAsc(anyCollection());
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String user, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void bankWideRolesSeeEveryCpButOnlyForThemselves() {
        when(cacheRepo.findAllCpIds()).thenReturn(List.of("CP-2", "CP-1"));
        signIn("auditor-1", "AUDITOR");
        assertThat(service.visibleCpIds("auditor-1")).containsExactly("CP-1", "CP-2");
        // An admin's request that works out an RM's portfolio still gets the RM's own CPs.
        signIn("admin-1", "ADMIN");
        when(cacheRepo.findCpIdsOwnedBy(USER)).thenReturn(List.of("CP-9"));
        assertThat(service.visibleCpIds(USER)).containsExactly("CP-9");
        // An RM is never bank-wide.
        signIn(USER, "RM");
        assertThat(service.visibleCpIds(USER)).containsExactly("CP-9");
    }

    @Test
    void ownedSummaryIgnoresBankWideRole() {
        signIn(USER, "SUPPORT");
        when(cacheRepo.findCpIdsOwnedBy(USER)).thenReturn(List.of());
        assertThat(service.ownedSummary(USER)).isEmpty();
        assertThat(service.ownedSummary(null)).isEmpty();
        assertThat(service.ownedSummary(" ")).isEmpty();
        verify(cacheRepo, never()).findAllCpIds();
    }

    @Test
    void summaryFailsClosedWhenPortfolioEmpty() {
        when(cacheRepo.findCpIdsOwnedBy(USER)).thenReturn(List.of());
        assertThat(service.summary(USER)).isEmpty();
        verify(appliedRepo, never()).findByCpIdInOrderByIdAsc(anyCollection());
    }

    @Test
    void summaryForMatchesAggregateCountsForThatCp() {
        // US-MON-14 AC3: the daily run's per-CP counts must equal the CP Monitoring screen's row.
        AppliedCovenant compliant = newApplied("1", "CP-1");
        AppliedCovenant breached = newApplied("2", "CP-1");
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(compliant, breached));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.COMPLIANT, compliant));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("2"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.BREACHED, breached));

        CpMonitoringSummary s = service.summaryFor("CP-1");

        assertThat(s.cpId()).isEqualTo("CP-1");
        assertThat(s.applied()).isEqualTo(2);
        assertThat(s.compliant()).isEqualTo(1);
        assertThat(s.breached()).isEqualTo(1);
        assertThat(s.error()).isZero();
        assertThat(s.pendingReview()).isZero();
    }

    @Test
    void summaryForCountsNeverAssessedCovenantAsPending() {
        AppliedCovenant never = newApplied("1", "CP-9");
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-9")).thenReturn(List.of(never));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        CpMonitoringSummary s = service.summaryFor("CP-9");

        assertThat(s.applied()).isEqualTo(1);
        assertThat(s.pendingReview()).isEqualTo(1);
        assertThat(s.compliant()).isZero();
    }

    @Test
    void scheduleSearchMatchesCpId() {
        AppliedCovenant a1 = newApplied("1", "CP-ALPHA");
        a1.setStartDate(LocalDate.now().minusYears(5));
        AppliedCovenant a2 = newApplied("2", "CP-BETA");
        a2.setStartDate(LocalDate.now().minusYears(5));
        ownPortfolio(List.of(a1, a2));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(Page.empty());
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("2"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        List<MonitoringEvent> events = service.schedule(null, null, null, null, "ALPHA", USER);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).cpId()).isEqualTo("CP-ALPHA");
    }

    @Test
    void covenantsForCpReturnsRowPerApplied() {
        AppliedCovenant a = newApplied("1", "CP-1");
        MonitoringSchedule schedule = new MonitoringSchedule();
        schedule.setNextRunAt(Instant.parse("2026-07-01T02:00:00Z"));
        schedule.setLastRunAt(Instant.parse("2026-06-01T02:00:00Z"));
        schedule.setQuartzJobKey("assessment-1");
        schedule.setCronExpression(Frequency.DAILY.defaultCron());
        schedule.setStatus(ScheduleStatus.SCHEDULED);

        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(a));
        when(scheduleRepo.findByAppliedCovenantId("1")).thenReturn(Optional.of(schedule));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.COMPLIANT, a));

        List<CovenantOnCp> result = service.covenantsForCp("CP-1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).latestResult()).isEqualTo(AssessmentResult.COMPLIANT);
        assertThat(result.get(0).nextRunAt()).isNotNull();
        assertThat(result.get(0).status()).isEqualTo(AppliedCovenantStatus.ACTIVE);
        assertThat(result.get(0).category()).isEqualTo(CovenantCategory.FINANCIAL);
    }

    @Test
    void covenantsForCpWithoutScheduleReturnsNullTimestamps() {
        AppliedCovenant a = newApplied("1", "CP-1");
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(a));
        when(scheduleRepo.findByAppliedCovenantId("1")).thenReturn(Optional.empty());
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        CovenantOnCp row = service.covenantsForCp("CP-1").get(0);
        assertThat(row.nextRunAt()).isNull();
        assertThat(row.lastRunAt()).isNull();
        assertThat(row.latestResult()).isNull();
    }

    @Test
    void covenantsForCpPopulatesDerivedTiming() {
        AppliedCovenant a = newApplied("1", "CP-1");
        a.setStartDate(LocalDate.now().minusYears(5)); // started long ago, never assessed → overdue
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(a));
        when(scheduleRepo.findByAppliedCovenantId("1")).thenReturn(Optional.empty());
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        CovenantOnCp row = service.covenantsForCp("CP-1").get(0);
        assertThat(row.timingStatus()).isEqualTo(TimingStatus.OVERDUE);
        assertThat(row.nextDueDate()).isNotNull();
        assertThat(row.overdueByDays()).isPositive();
        assertThat(row.latestAssessmentId()).isNull();
    }

    @Test
    void covenantsForCpDefersTimingForActiveExtension() {
        // US-MON-21: the CP review grid applies the same extension-defer rule as the portfolio agenda —
        // an active Extension moves the covenant's next monitoring date to the grace date.
        AppliedCovenant a = newApplied("1", "CP-1");
        a.setStartDate(LocalDate.now().minusYears(5)); // overdue on the base frequency schedule
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(a));
        when(scheduleRepo.findByAppliedCovenantId("1")).thenReturn(Optional.empty());
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.BREACHED, a, "asmt-1",
                        Instant.now().minus(Duration.ofDays(400))));
        LocalDate grace = LocalDate.now().plusDays(20);
        CovenantDisposition ext = disposition("CP-1", "1", "asmt-1", CovenantDispositionStatus.EXTENSION);
        ext.setExtensionDate(grace);
        when(dispositionRepo.findByCpId("CP-1")).thenReturn(List.of(ext));

        CovenantOnCp row = service.covenantsForCp("CP-1").get(0);
        assertThat(row.timingStatus()).isEqualTo(TimingStatus.UP_TO_DATE);
        assertThat(row.nextDueDate()).isEqualTo(grace);
        assertThat(row.overdueByDays()).isZero();
    }

    @Test
    void covenantsForCpScopedReturnsRowsWhenOwned() {
        // US-MON-09 drill-in guard: an owned CP returns its covenants via the scoped entrypoint.
        AppliedCovenant a = newApplied("1", "CP-1");
        when(cacheRepo.findCpIdsOwnedBy(USER)).thenReturn(List.of("CP-1"));
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(a));
        when(scheduleRepo.findByAppliedCovenantId("1")).thenReturn(Optional.empty());
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        assertThat(service.covenantsForCp("CP-1", USER)).hasSize(1);
    }

    @Test
    void covenantsForCpScopedReturnsEmptyWhenNotOwned() {
        // A CP outside the caller's portfolio (or a blank user) never reveals covenants.
        when(cacheRepo.findCpIdsOwnedBy(USER)).thenReturn(List.of("CP-2"));
        assertThat(service.covenantsForCp("CP-1", USER)).isEmpty();
        assertThat(service.covenantsForCp("CP-1", null)).isEmpty();
        verify(appliedRepo, never()).findByCpIdOrderByIdAsc("CP-1");
    }

    @Test
    void scheduleAggregatesActiveCovenantsOverdueFirstExcludingSuspended() {
        AppliedCovenant overdue = newApplied("1", "CP-1");
        overdue.setStartDate(LocalDate.now().minusYears(5));
        AppliedCovenant upcoming = newApplied("2", "CP-2");
        upcoming.setStartDate(LocalDate.now().plusDays(5)); // not started yet, within default window
        AppliedCovenant suspended = newApplied("3", "CP-3");
        suspended.setStartDate(LocalDate.now().minusYears(5));
        suspended.setStatus(AppliedCovenantStatus.SUSPENDED);

        AppliedCovenant farFuture = newApplied("4", "CP-4");
        farFuture.setStartDate(LocalDate.now().plusDays(60)); // starts beyond the default 30-day window

        ownPortfolio(List.of(overdue, upcoming, suspended, farFuture));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(Page.empty());
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("2"), any(PageRequest.class)))
                .thenReturn(Page.empty());
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("4"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        List<MonitoringEvent> events = service.schedule(null, null, null, null, null, USER);

        assertThat(events).hasSize(2); // suspended excluded; far-future outside the window excluded
        assertThat(events.get(0).timingStatus()).isEqualTo(TimingStatus.OVERDUE); // overdue pinned first
        assertThat(events).anyMatch(e -> e.timingStatus() == TimingStatus.NOT_STARTED);
        assertThat(events).noneMatch(e -> e.cpId().equals("CP-3"));
        assertThat(events).noneMatch(e -> e.cpId().equals("CP-4"));
    }

    @Test
    void scheduleSearchFiltersByCovenantName() {
        AppliedCovenant a1 = newApplied("1", "CP-1"); // master name "M-1"
        a1.setStartDate(LocalDate.now().minusYears(5));
        AppliedCovenant a2 = newApplied("2", "CP-2"); // master name "M-2"
        a2.setStartDate(LocalDate.now().minusYears(5));
        ownPortfolio(List.of(a1, a2));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(Page.empty());
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("2"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        List<MonitoringEvent> events = service.schedule(null, null, null, null, "M-2", USER);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).cpId()).isEqualTo("CP-2");
    }

    @Test
    void scheduleExcludesEndedCovenants() {
        AppliedCovenant ended = newApplied("1", "CP-1");
        ended.setStartDate(LocalDate.now().minusYears(5));
        ended.setEndDate(LocalDate.now().minusYears(1));
        ownPortfolio(List.of(ended));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        assertThat(service.schedule(null, null, null, null, null, USER)).isEmpty();
    }

    @Test
    void scheduleFiltersByStatusBucket() {
        AppliedCovenant overdue = newApplied("1", "CP-1");
        overdue.setStartDate(LocalDate.now().minusYears(5));
        AppliedCovenant upcoming = newApplied("2", "CP-2");
        upcoming.setStartDate(LocalDate.now().plusDays(5));
        ownPortfolio(List.of(overdue, upcoming));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(Page.empty());
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("2"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        List<MonitoringEvent> overdueOnly =
                service.schedule(null, null, TimingStatus.OVERDUE, null, null, USER);
        assertThat(overdueOnly).hasSize(1);
        assertThat(overdueOnly.get(0).timingStatus()).isEqualTo(TimingStatus.OVERDUE);
        assertThat(overdueOnly.get(0).cpId()).isEqualTo("CP-1");
    }

    @Test
    void scheduleFiltersByCp() {
        AppliedCovenant a = newApplied("2", "CP-2");
        a.setStartDate(LocalDate.now().plusDays(5));
        when(cacheRepo.findCpIdsOwnedBy(USER)).thenReturn(List.of("CP-2"));
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-2")).thenReturn(List.of(a));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("2"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        List<MonitoringEvent> events = service.schedule(null, null, null, "CP-2", null, USER);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).cpId()).isEqualTo("CP-2");
    }

    @Test
    void scheduleWithCpOutsidePortfolioReturnsEmpty() {
        // Requesting a specific CP the caller does not own yields nothing (no repo hit for that CP).
        when(cacheRepo.findCpIdsOwnedBy(USER)).thenReturn(List.of("CP-2"));

        assertThat(service.schedule(null, null, null, "CP-1", null, USER)).isEmpty();
        verify(appliedRepo, never()).findByCpIdOrderByIdAsc("CP-1");
    }

    @Test
    void scheduleFailsClosedForBlankUser() {
        assertThat(service.schedule(null, null, null, null, null, null)).isEmpty();
        verify(cacheRepo, never()).findCpIdsOwnedBy(any());
    }

    @Test
    void historyDelegates() {
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(Page.empty());
        assertThat(service.historyFor("1", 0, 25)).isEmpty();
        assertThat(service.historyFor("1", -3, 100_000)).isEmpty();
        verify(assessmentRepo).findByAppliedCovenantIdOrderByScheduledAtDesc("1", PageRequest.of(0, 200));
    }

    // ---- US-MON-20: By-CP handled (dispositioned) count -------------------------------------

    @Test
    void summaryCountsActiveDispositionAsHandledWithoutReducingBreached() {
        // AC1/AC2: two breaches on the CP, one carrying an active Waiver → breached stays 2, handled = 1.
        AppliedCovenant a1 = newApplied("1", "CP-1");
        AppliedCovenant a2 = newApplied("2", "CP-1");
        ownPortfolio(List.of(a1, a2));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.BREACHED, a1, "asmt-1"));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("2"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.BREACHED, a2, "asmt-2"));
        when(dispositionRepo.findByCpIdIn(anyCollection())).thenReturn(List.of(
                disposition("CP-1", "1", "asmt-1", CovenantDispositionStatus.WAIVER)));

        CpMonitoringSummary cp1 = service.summary(USER).get(0);
        assertThat(cp1.breached()).isEqualTo(2);
        assertThat(cp1.dispositioned()).isEqualTo(1);
    }

    @Test
    void summaryIgnoresLapsedDispositionAgainstOlderAssessment() {
        // AC3: a Waiver pinned to a superseded assessment is lapsed → not handled, breach still open.
        AppliedCovenant a1 = newApplied("1", "CP-1");
        ownPortfolio(List.of(a1));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.BREACHED, a1, "asmt-current"));
        when(dispositionRepo.findByCpIdIn(anyCollection())).thenReturn(List.of(
                disposition("CP-1", "1", "asmt-OLD", CovenantDispositionStatus.WAIVER)));

        CpMonitoringSummary cp1 = service.summary(USER).get(0);
        assertThat(cp1.breached()).isEqualTo(1);
        assertThat(cp1.dispositioned()).isZero();
    }

    @Test
    void summaryForCountsActiveDispositionAsHandled() {
        // The daily-run per-CP path (summaryFor) reads dispositions the same way as the portfolio roll-up.
        AppliedCovenant a1 = newApplied("1", "CP-1");
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(a1));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.BREACHED, a1, "asmt-1"));
        when(dispositionRepo.findByCpId("CP-1")).thenReturn(List.of(
                disposition("CP-1", "1", "asmt-1", CovenantDispositionStatus.EXTENSION)));

        CpMonitoringSummary s = service.summaryFor("CP-1");
        assertThat(s.breached()).isEqualTo(1);
        assertThat(s.dispositioned()).isEqualTo(1);
    }

    // ---- US-MON-21: By-Covenant agenda disposition badge ------------------------------------

    @Test
    void scheduleDefersOverdueCovenantWithActiveExtension() {
        // AC1/AC2: an overdue breach carrying an active Extension is deferred — the grace date becomes
        // the next monitoring date (clearing OVERDUE) and the event gains the Extension badge + date.
        AppliedCovenant overdue = newApplied("1", "CP-1");
        overdue.setStartDate(LocalDate.now().minusYears(5));
        ownPortfolio(List.of(overdue));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.BREACHED, overdue, "asmt-1",
                        Instant.now().minus(Duration.ofDays(400))));
        LocalDate grace = LocalDate.now().plusDays(20); // future, beyond the 7-day horizon, within window
        CovenantDisposition ext = disposition("CP-1", "1", "asmt-1", CovenantDispositionStatus.EXTENSION);
        ext.setExtensionDate(grace);
        when(dispositionRepo.findByCpIdIn(anyCollection())).thenReturn(List.of(ext));

        MonitoringEvent e = service.schedule(null, null, null, null, null, USER).get(0);
        assertThat(e.timingStatus()).isEqualTo(TimingStatus.UP_TO_DATE);
        assertThat(e.nextDueDate()).isEqualTo(grace);
        assertThat(e.overdueByDays()).isZero();
        assertThat(e.dispositionStatus()).isEqualTo(CovenantDispositionStatus.EXTENSION);
        assertThat(e.extensionDate()).isEqualTo(grace);
    }

    @Test
    void scheduleWithActiveWaiverKeepsOverdueTiming() {
        // A Waiver carries no grace date, so the covenant stays in its OVERDUE bucket; only the badge
        // (status, no date) is added — deferral is Extension-specific.
        AppliedCovenant overdue = newApplied("1", "CP-1");
        overdue.setStartDate(LocalDate.now().minusYears(5));
        ownPortfolio(List.of(overdue));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.BREACHED, overdue, "asmt-1",
                        Instant.now().minus(Duration.ofDays(400))));
        when(dispositionRepo.findByCpIdIn(anyCollection())).thenReturn(List.of(
                disposition("CP-1", "1", "asmt-1", CovenantDispositionStatus.WAIVER)));

        MonitoringEvent e = service.schedule(null, null, null, null, null, USER).get(0);
        assertThat(e.timingStatus()).isEqualTo(TimingStatus.OVERDUE);
        assertThat(e.overdueByDays()).isPositive();
        assertThat(e.dispositionStatus()).isEqualTo(CovenantDispositionStatus.WAIVER);
        assertThat(e.extensionDate()).isNull();
    }

    @Test
    void scheduleDoesNotBadgeLapsedDisposition() {
        // AC3: a Waiver against a superseded assessment shows no badge (event reads as un-handled).
        AppliedCovenant overdue = newApplied("1", "CP-1");
        overdue.setStartDate(LocalDate.now().minusYears(5));
        ownPortfolio(List.of(overdue));
        when(assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(eq("1"), any(PageRequest.class)))
                .thenReturn(latestPage(AssessmentResult.BREACHED, overdue, "asmt-current",
                        Instant.now().minus(Duration.ofDays(400))));
        when(dispositionRepo.findByCpIdIn(anyCollection())).thenReturn(List.of(
                disposition("CP-1", "1", "asmt-OLD", CovenantDispositionStatus.WAIVER)));

        MonitoringEvent e = service.schedule(null, null, null, null, null, USER).get(0);
        assertThat(e.dispositionStatus()).isNull();
        assertThat(e.extensionDate()).isNull();
    }

    private static Page<Assessment> latestPage(AssessmentResult result, AppliedCovenant applied,
                                               String assessmentId) {
        return latestPage(result, applied, assessmentId, Instant.now());
    }

    private static Page<Assessment> latestPage(AssessmentResult result, AppliedCovenant applied,
                                               String assessmentId, Instant executedAt) {
        Assessment a = new Assessment();
        setId(a, assessmentId);
        a.setAppliedCovenant(applied);
        a.setResult(result);
        a.setExecutedAt(executedAt);
        a.setScheduledAt(executedAt);
        return new PageImpl<>(List.of(a));
    }

    private static CovenantDisposition disposition(String cpId, String appliedId, String assessmentId,
                                                   CovenantDispositionStatus status) {
        CovenantDisposition d = new CovenantDisposition();
        d.setCpId(cpId);
        d.setAppliedCovenantId(appliedId);
        d.setAssessmentId(assessmentId);
        d.setStatus(status);
        return d;
    }

    private static AppliedCovenant newApplied(String id, String cpId) {
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
        a.setStartDate(LocalDate.now());
        a.setStatus(AppliedCovenantStatus.ACTIVE);
        a.setSnapshotJson("{}");
        return a;
    }

    private static Page<Assessment> latestPage(AssessmentResult result, AppliedCovenant applied) {
        Assessment a = new Assessment();
        a.setAppliedCovenant(applied);
        a.setResult(result);
        a.setExecutedAt(Instant.now());
        a.setScheduledAt(Instant.now());
        return new PageImpl<>(List.of(a));
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
