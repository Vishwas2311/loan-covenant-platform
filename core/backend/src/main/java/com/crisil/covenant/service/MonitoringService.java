package com.crisil.covenant.service;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.domain.CovenantOnCp;
import com.crisil.covenant.domain.CpMonitoringSummary;
import com.crisil.covenant.domain.MonitoringEvent;
import com.crisil.covenant.domain.MonitoringTiming;
import com.crisil.covenant.dto.ReportingSettingsResponse;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.entity.CovenantDisposition;
import com.crisil.covenant.entity.MonitoringSchedule;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.TimingStatus;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.AssessmentRepository.LatestOutcome;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.repository.CovenantDispositionRepository;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.repository.MonitoringScheduleRepository;
import com.crisil.covenant.security.Roles;
import com.crisil.covenant.util.DispositionRules;
import com.crisil.covenant.util.IdBatches;
import com.crisil.covenant.util.MonitoringTimingCalculator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.hibernate.Hibernate;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MonitoringService {

    static final int MAX_PAGE_SIZE = 200;

    private final AppliedCovenantRepository appliedRepo;
    private final AssessmentRepository assessmentRepo;
    private final MonitoringScheduleRepository scheduleRepo;
    private final ReportingSettingsService reportingSettingsService;
    private final CreditProposalCacheRepository cacheRepo;
    private final CovenantDispositionRepository dispositionRepo;

    public MonitoringService(AppliedCovenantRepository appliedRepo,
                             AssessmentRepository assessmentRepo,
                             MonitoringScheduleRepository scheduleRepo,
                             ReportingSettingsService reportingSettingsService,
                             CreditProposalCacheRepository cacheRepo,
                             CovenantDispositionRepository dispositionRepo) {
        this.appliedRepo = appliedRepo;
        this.assessmentRepo = assessmentRepo;
        this.scheduleRepo = scheduleRepo;
        this.reportingSettingsService = reportingSettingsService;
        this.cacheRepo = cacheRepo;
        this.dispositionRepo = dispositionRepo;
    }

    /**
     * US-MON-09 / US-MON-15 portfolio scoping: the set of CP ids the given user may see, i.e. those on
     * which they are the RM or WRM. Fails <em>closed</em> — a blank/null user (no resolved identity)
     * yields an <em>empty</em> set, so an unidentified caller sees nothing rather than everything.
     */
    private Set<String> ownedCpIds(String user) {
        if (user == null || user.isBlank()) {
            return Set.of();
        }
        if (bankWide(user)) {
            return new HashSet<>(cacheRepo.findAllCpIds());
        }
        return new HashSet<>(cacheRepo.findCpIdsOwnedBy(user));
    }

    /**
     * Admin, auditor and support may open any CP (see CpAccessGuard), so their portfolio is every CP.
     * Only when {@code user} is the signed-in caller: a job or an admin request working out another
     * user's portfolio (an RM's breach e-mail, say) still gets that user's own CPs.
     */
    private static boolean bankWide(String user) {
        return CurrentUserContext.currentUser().filter(user::equals).isPresent()
                && CurrentUserContext.hasAnyRole(Roles.ADMIN, Roles.AUDITOR, Roles.SUPPORT);
    }

    /** The CP ids {@code user} sees in their portfolio, sorted. */
    public List<String> visibleCpIds(String user) {
        return ownedCpIds(user).stream().sorted().toList();
    }

    /** The CPs {@code user} is RM or WRM on, whatever their role: for things that wait on that person. */
    public List<CpMonitoringSummary> ownedSummary(String user) {
        if (user == null || user.isBlank()) {
            return List.of();
        }
        return summaryOf(new HashSet<>(cacheRepo.findCpIdsOwnedBy(user)));
    }

    public List<CpMonitoringSummary> summary(String user) {
        return summaryOf(ownedCpIds(user));
    }

    /** Every cached CP's summary, whoever asks: for system jobs such as the daily escalation. */
    public List<CpMonitoringSummary> allSummary() {
        return summaryOf(new HashSet<>(cacheRepo.findAllCpIds()));
    }

    private final PortfolioCache<Set<String>, List<CpMonitoringSummary>> summaries =
            new PortfolioCache<>(Duration.ofSeconds(30), 500);

    private List<CpMonitoringSummary> summaryOf(Set<String> owned) {
        if (owned.isEmpty()) {
            return List.of();
        }
        return summaries.get(Set.copyOf(owned), () -> computeSummary(owned));
    }

    private List<CpMonitoringSummary> computeSummary(Set<String> owned) {
        Map<String, CovenantDisposition> dispByAc = dispositionsByApplied(IdBatches.fetch(owned, dispositionRepo::findByCpIdIn));
        Map<String, long[]> byCp = new TreeMap<>();
        List<AppliedCovenant> applied = appliedOn(owned);
        Map<String, LatestOutcome> latest = latestOutcomes(applied);
        for (AppliedCovenant ac : applied) {
            accumulate(byCp.computeIfAbsent(ac.getCpId(), k -> new long[6]), ac, dispByAc, latest.get(ac.getId()));
        }
        return byCp.entrySet().stream()
                .map(e -> toSummary(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Per-CP monitoring counts identical to one row of {@link #summary()}. Extracted so the CP
     * Monitoring screen and the daily breach-notification run (US-MON-14 AC3) share one counting
     * implementation — same Applied / Compliant / Breached / Error / Pending semantics, with NA
     * counted only toward the total.
     */
    public CpMonitoringSummary summaryFor(String cpId) {
        Map<String, CovenantDisposition> dispByAc = dispositionsByApplied(dispositionRepo.findByCpId(cpId));
        long[] counts = new long[6];
        List<AppliedCovenant> applied = appliedRepo.findByCpIdOrderByIdAsc(cpId);
        Map<String, LatestOutcome> latest = latestOutcomes(applied);
        for (AppliedCovenant ac : applied) {
            accumulate(counts, ac, dispByAc, latest.get(ac.getId()));
        }
        return toSummary(cpId, counts);
    }

    /**
     * Fold one applied covenant into the
     * {@code [applied, compliant, breached, error, pending, dispositioned]} tally. {@code dispositioned}
     * (US-MON-20) counts a breach as handled when it carries an <em>active</em> Waiver/Extension against
     * its latest assessment (the shared {@link DispositionRules#addressesCurrentBreach} rule); it never
     * reduces {@code breached}.
     */
    private static void accumulate(long[] counts, AppliedCovenant ac, Map<String, CovenantDisposition> dispByAc,
                                   LatestOutcome latest) {
        counts[0]++;
        AssessmentResult result = latest == null ? null : latest.getResult();
        if (result != null) {
            switch (result) {
                case COMPLIANT -> counts[1]++;
                case BREACHED -> {
                    counts[2]++;
                    if (DispositionRules.addressesCurrentBreach(dispByAc.get(ac.getId()), latest.getId())) {
                        counts[5]++;
                    }
                }
                case ERROR -> counts[3]++;
                case PENDING_REVIEW -> counts[4]++;
                // NA = value-only covenant (no breach test): informational, not a
                // compliance bucket, so it is intentionally counted only in the total.
                default -> { }
            }
        } else {
            counts[4]++;
        }
    }

    /** The covenants on {@code cps}, by id, in batches below SQL Server's 2,100-parameter limit. */
    private List<AppliedCovenant> appliedOn(Collection<String> cps) {
        List<AppliedCovenant> applied = IdBatches.fetch(cps, appliedRepo::findByCpIdInOrderByIdAsc);
        applied.sort(Comparator.comparing(AppliedCovenant::getId));
        return applied;
    }

    /** Each covenant's latest assessment, in batches below SQL Server's 2,100-parameter limit. */
    private Map<String, LatestOutcome> latestOutcomes(List<AppliedCovenant> applied) {
        List<String> ids = applied.stream().map(AppliedCovenant::getId).toList();
        Map<String, LatestOutcome> byId = new HashMap<>();
        for (LatestOutcome o : IdBatches.fetch(ids, assessmentRepo::findLatestOutcomes)) {
            // Two rows only when scheduledAt ties; keep one, as the paged lookup did.
            byId.putIfAbsent(o.getAppliedCovenantId(), o);
        }
        return byId;
    }

    private static CpMonitoringSummary toSummary(String cpId, long[] c) {
        return new CpMonitoringSummary(cpId, c[0], c[1], c[2], c[3], c[4], c[5]);
    }

    /** Index dispositions by their applied-covenant id (globally unique) for O(1) per-covenant lookup. */
    private static Map<String, CovenantDisposition> dispositionsByApplied(List<CovenantDisposition> dispositions) {
        Map<String, CovenantDisposition> byAc = new HashMap<>();
        for (CovenantDisposition d : dispositions) {
            byAc.put(d.getAppliedCovenantId(), d);
        }
        return byAc;
    }

    /**
     * US-MON-09 / US-MON-15 drill-in guard: the covenants applied to {@code cpId}, but only when that CP
     * is inside the caller's portfolio (RM/WRM). A CP outside the portfolio (or a blank user) yields an
     * empty list. This is the entrypoint the Monitoring controller uses.
     */
    public List<CovenantOnCp> covenantsForCp(String cpId, String user) {
        if (!ownedCpIds(user).contains(cpId)) {
            return List.of();
        }
        return covenantsForCp(cpId);
    }

    /**
     * The covenants applied to a CP, <em>unscoped</em>. Reserved for internal callers that have already
     * established the caller's access to the CP (e.g. the assessment/review grid, US-MON-16). Portfolio
     * visibility for the monitoring surface goes through {@link #covenantsForCp(String, String)}.
     */
    public List<CovenantOnCp> covenantsForCp(String cpId) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        int dueSoonDays = reportingSettingsService.current().dueSoonDays();
        // US-MON-21: batch the CP's dispositions so an active EXTENSION can defer the covenant's next
        // monitoring date consistently with the portfolio agenda (see schedule()).
        Map<String, CovenantDisposition> dispByAc = dispositionsByApplied(dispositionRepo.findByCpId(cpId));
        List<AppliedCovenant> applied = appliedRepo.findByCpIdOrderByIdAsc(cpId);
        Map<String, Assessment> latest = latestAssessments(applied);
        Map<String, MonitoringSchedule> schedules = schedulesOf(applied);
        return applied.stream()
                .map(ac -> toCovenantOnCp(ac, today, zone, dueSoonDays, dispByAc.get(ac.getId()), latest.get(ac.getId()),
                        schedules.get(ac.getId())))
                .toList();
    }

    /**
     * {@link #covenantsForCp(String)} for many CPs in a few batched queries, keyed by CP id in the order
     * given; a CP without covenants maps to an empty list. Unscoped, like the single-CP form.
     */
    public Map<String, List<CovenantOnCp>> covenantsForCps(Collection<String> cpIds) {
        Map<String, List<CovenantOnCp>> byCp = new LinkedHashMap<>();
        cpIds.forEach(cp -> byCp.put(cp, new ArrayList<>()));
        if (cpIds.isEmpty()) {
            return byCp;
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        int dueSoonDays = reportingSettingsService.current().dueSoonDays();
        Map<String, CovenantDisposition> dispByAc = dispositionsByApplied(IdBatches.fetch(cpIds, dispositionRepo::findByCpIdIn));
        List<AppliedCovenant> applied = appliedOn(cpIds);
        Map<String, Assessment> latest = latestAssessments(applied);
        Map<String, MonitoringSchedule> schedules = schedulesOf(applied);
        for (AppliedCovenant ac : applied) {
            byCp.computeIfAbsent(ac.getCpId(), k -> new ArrayList<>()).add(toCovenantOnCp(ac, today, zone, dueSoonDays,
                    dispByAc.get(ac.getId()), latest.get(ac.getId()), schedules.get(ac.getId())));
        }
        return byCp;
    }

    /** Each covenant's schedule row, in one query per 500 covenants instead of one per covenant. */
    private Map<String, MonitoringSchedule> schedulesOf(List<AppliedCovenant> applied) {
        Map<String, MonitoringSchedule> byApplied = new HashMap<>();
        for (MonitoringSchedule s : IdBatches.fetch(applied.stream().map(AppliedCovenant::getId).toList(),
                scheduleRepo::findByAppliedCovenantIdIn)) {
            byApplied.put(s.getAppliedCovenant().getId(), s);
        }
        return byApplied;
    }

    /** Each covenant's latest assessment entity: one query for the ids, one to load them. */
    private Map<String, Assessment> latestAssessments(List<AppliedCovenant> applied) {
        Map<String, LatestOutcome> outcomes = latestOutcomes(applied);
        Map<String, Assessment> byId = new HashMap<>();
        for (Assessment a : IdBatches.fetch(outcomes.values().stream().map(LatestOutcome::getId).toList(), assessmentRepo::findAllById)) {
            byId.put(a.getId(), a);
        }
        Map<String, Assessment> byApplied = new HashMap<>();
        outcomes.forEach((appliedId, o) -> {
            Assessment a = byId.get(o.getId());
            if (a != null) {
                byApplied.put(appliedId, a);
            }
        });
        return byApplied;
    }

    private CovenantOnCp toCovenantOnCp(AppliedCovenant ac, LocalDate today, ZoneId zone,
                                        int dueSoonDays, CovenantDisposition disp, Assessment latestAssessment,
                                        MonitoringSchedule schedule) {
        Optional<Assessment> latest = Optional.ofNullable(latestAssessment);
        MonitoringTiming timing = timingFor(ac, latest.orElse(null), today, zone, dueSoonDays);
        // US-MON-21: an active EXTENSION defers the next monitoring date (grace date, or an earlier
        // upcoming frequency date). Same rule as the agenda so both surfaces agree.
        if (DispositionRules.addressesCurrentBreach(disp, latest.map(Assessment::getId).orElse(null))
                && disp.getExtensionDate() != null) {
            timing = MonitoringTimingCalculator.withExtension(
                    timing, disp.getExtensionDate(), today, dueSoonDays);
        }
        String condition = ac.getConditionExpression();
        if (condition == null || condition.isBlank()) {
            condition = ac.getCovenantMaster().getConditionExpression();
        }
        return new CovenantOnCp(
                ac.getId(),
                ac.getCovenantMaster().getId(),
                ac.getCovenantMaster().getName(),
                ac.getCovenantMaster().getCategory(),
                ac.getFrequency(),
                ac.getStartDate(),
                ac.getStatus(),
                schedule == null ? null : schedule.getNextRunAt(),
                schedule == null ? null : schedule.getLastRunAt(),
                latest.map(Assessment::getResult).orElse(null),
                latest.map(Assessment::getExecutedAt).orElse(null),
                latest.map(Assessment::getId).orElse(null),
                timing.nextDueDate(),
                timing.status(),
                timing.overdueByDays(),
                condition,
                ac.getConditionType(),
                ac.getClassification(),
                ac.getCovenantMaster().getAssessmentType());
    }

    /**
     * US-MON-09: a time-ordered list of monitoring events across CPs. {@code from}/{@code to} bound
     * the window (defaults to today .. today+30); overdue events are always included. {@code status}
     * (a bucket), {@code cpId}, and {@code q} (covenant/CP search) further narrow the result.
     */
    public List<MonitoringEvent> schedule(LocalDate from, LocalDate to, TimingStatus status,
                                          String cpId, String q, String user) {
        Set<String> owned = ownedCpIds(user);
        if (owned.isEmpty()) {
            return List.of();
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        ReportingSettingsResponse settings = reportingSettingsService.current();
        int dueSoonDays = settings.dueSoonDays();
        LocalDate windowFrom = from != null ? from : today;
        LocalDate windowTo = to != null ? to : today.plusDays(settings.upcomingWindowDays());
        String needle = (q == null || q.isBlank()) ? null : q.trim().toLowerCase(Locale.ROOT);

        List<AppliedCovenant> applied;
        if (cpId == null || cpId.isBlank()) {
            applied = appliedOn(owned);
        } else if (owned.contains(cpId)) {
            applied = appliedRepo.findByCpIdOrderByIdAsc(cpId);
        } else {
            // Requested a CP outside the caller's portfolio: nothing to show.
            return List.of();
        }
        // US-MON-21: batch-load dispositions parallel to the applied selection (one query, no N+1)
        // so each event can carry its effective (active-only) Waiver/Extension badge.
        Map<String, CovenantDisposition> dispByAc = dispositionsByApplied(
                cpId == null || cpId.isBlank()
                        ? IdBatches.fetch(owned, dispositionRepo::findByCpIdIn)
                        : dispositionRepo.findByCpId(cpId));

        List<MonitoringEvent> events = new ArrayList<>();
        Map<String, Assessment> latestById = latestAssessments(applied.stream()
                .filter(ac -> ac.getStatus() == AppliedCovenantStatus.ACTIVE).toList());
        for (AppliedCovenant ac : applied) {
            if (ac.getStatus() != AppliedCovenantStatus.ACTIVE) {
                continue;
            }
            Optional<Assessment> latest = Optional.ofNullable(latestById.get(ac.getId()));
            // US-MON-21: badge only an *active* disposition (same rule as the grid / US-MON-20); a
            // lapsed one against an older assessment shows nothing.
            String latestAssessmentId = latest.map(Assessment::getId).orElse(null);
            CovenantDisposition disp = dispByAc.get(ac.getId());
            boolean handled = DispositionRules.addressesCurrentBreach(disp, latestAssessmentId);

            MonitoringTiming timing = timingFor(ac, latest.orElse(null), today, zone, dueSoonDays);
            // US-MON-21: an active EXTENSION defers the covenant — its next monitoring date becomes
            // the grace date (or an earlier upcoming frequency date), so status/window re-derive from
            // it. Applied *before* the bucket/window filters so an extended covenant is bucketed by
            // its effective date and no longer surfaces as overdue. A Waiver carries no date and
            // leaves timing untouched.
            if (handled && disp.getExtensionDate() != null) {
                timing = MonitoringTimingCalculator.withExtension(
                        timing, disp.getExtensionDate(), today, dueSoonDays);
            }
            if (timing.status() == TimingStatus.ENDED) {
                continue;
            }
            if (status != null && timing.status() != status) {
                continue;
            }
            LocalDate relevant = relevantDate(ac, timing);
            boolean inWindow = timing.status() == TimingStatus.OVERDUE
                    || (relevant != null && !relevant.isBefore(windowFrom) && !relevant.isAfter(windowTo));
            if (!inWindow) {
                continue;
            }
            String covName = ac.getCovenantMaster().getName();
            if (needle != null
                    && !covName.toLowerCase(Locale.ROOT).contains(needle)
                    && !ac.getCpId().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            events.add(new MonitoringEvent(
                    ac.getCpId(),
                    null,
                    ac.getId(),
                    ac.getCovenantMaster().getId(),
                    covName,
                    ac.getFrequency(),
                    ac.getStartDate(),
                    timing.nextDueDate(),
                    latest.map(Assessment::getExecutedAt).orElse(null),
                    latest.map(Assessment::getResult).orElse(null),
                    latestAssessmentId,
                    timing.status(),
                    timing.overdueByDays(),
                    handled ? disp.getStatus() : null,
                    handled ? disp.getExtensionDate() : null));
        }
        events.sort(Comparator.comparing(MonitoringService::sortKey,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }

    /** The date an event sorts/filters on: next due for started covenants, start date otherwise. */
    private static LocalDate relevantDate(AppliedCovenant ac, MonitoringTiming timing) {
        return timing.status() == TimingStatus.NOT_STARTED ? ac.getStartDate() : timing.nextDueDate();
    }

    private static LocalDate sortKey(MonitoringEvent e) {
        return e.timingStatus() == TimingStatus.NOT_STARTED ? e.startDate() : e.nextDueDate();
    }

    private MonitoringTiming timingFor(AppliedCovenant ac, Assessment latest, LocalDate today,
                                       ZoneId zone, int dueSoonDays) {
        Instant lastAssessedAt = latest == null ? null : latest.getExecutedAt();
        String cron = MonitoringTimingCalculator.effectiveCron(ac.getFrequency(), ac.getCronExpression());
        return MonitoringTimingCalculator.compute(
                cron, ac.getStartDate(), ac.getEndDate(), lastAssessedAt, today, zone, dueSoonDays);
    }

    public List<Assessment> historyFor(String appliedCovenantId, int page, int size) {
        // Same 200-row page cap as the pageable endpoints; a negative page or size reads the first page.
        List<Assessment> history = assessmentRepo.findByAppliedCovenantIdOrderByScheduledAtDesc(
                appliedCovenantId, PageRequest.of(Math.max(0, page), Math.min(MAX_PAGE_SIZE, Math.max(1, size))))
                .getContent();
        // US-ASM-21: force-init the lazy comparisons within this transaction so the read model can
        // expose them (the paginated query keeps a scalar graph to avoid in-memory pagination).
        history.forEach(a -> Hibernate.initialize(a.getComparisons()));
        return history;
    }
}
