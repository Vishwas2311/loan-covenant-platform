package com.crisil.covenant.service;

import com.crisil.covenant.domain.MonitoringTiming;
import com.crisil.covenant.dto.PreStageResult;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TimingStatus;
import com.crisil.covenant.config.IngestionProperties;
import com.crisil.covenant.formula.CompiledFormula;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaTerm;
import com.crisil.covenant.formula.SamplingMode;
import com.crisil.covenant.formula.Window;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.repository.AssessmentRepository.AppliedCovenantLastExecuted;
import com.crisil.covenant.repository.MeasureRepository;
import com.crisil.covenant.service.MeasureAggregationResolver.AggregationRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * US-DS-13 (AC4/AC5/AC11): the due-driven pre-stage sweep — the primary trigger that runs the freshness
 * gate <em>ahead of</em> evaluation so the breach run reads a fully staged copy (a pure local read).
 *
 * <p>Ingestion is deliberately orchestrated in its own Quartz job ({@link IngestionPreStageJob}),
 * separate from the breach run's failure domain. Each sweep:
 * <ol>
 *   <li>streams ACTIVE covenants a page at a time (bounded memory, one grouped last-executed query per
 *       page — never an N+1), keeping only those <b>due within the lookahead horizon</b>;</li>
 *   <li><b>coalesces</b> every due covenant's demand into one candidate per {@code (table, entity)}:
 *       obligor-keyed tables collapse all of an obligor's CPs onto the obligor grain, and the widest
 *       look-back window wins — so ~500 covenants sharing one obligor become <b>one</b> extract;</li>
 *   <li>runs {@link IngestionOrchestrationService#ingestIfStale the gate} on each candidate, isolating a
 *       failing entity so it is retried on a later sweep without blocking the rest.</li>
 * </ol>
 *
 * <p>Not {@code @Transactional}: like the breach run it orchestrates many independent units of work —
 * each gate decision manages its own transaction so one entity's failure cannot abort the sweep.
 */
@Service
public class IngestionPreStageService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionPreStageService.class);

    /** ACTIVE covenants are read a page at a time so a large book never loads at once. */
    private static final int ACTIVE_PAGE_SIZE = 500;

    private final AppliedCovenantRepository appliedRepo;
    private final AssessmentRepository assessmentRepo;
    private final FormulaEngine formulaEngine;
    private final MeasureCatalogService measureCatalog;
    private final MeasureRepository measureRepository;
    private final ObligorMappingService obligorMappingService;
    private final IngestionOrchestrationService ingestionOrchestration;
    private final IngestionProperties properties;

    public IngestionPreStageService(AppliedCovenantRepository appliedRepo,
                                    AssessmentRepository assessmentRepo,
                                    FormulaEngine formulaEngine,
                                    MeasureCatalogService measureCatalog,
                                    MeasureRepository measureRepository,
                                    ObligorMappingService obligorMappingService,
                                    IngestionOrchestrationService ingestionOrchestration,
                                    IngestionProperties properties) {
        this.appliedRepo = appliedRepo;
        this.assessmentRepo = assessmentRepo;
        this.formulaEngine = formulaEngine;
        this.measureCatalog = measureCatalog;
        this.measureRepository = measureRepository;
        this.obligorMappingService = obligorMappingService;
        this.ingestionOrchestration = ingestionOrchestration;
        this.properties = properties;
    }

    /**
     * Pre-stage every entity with a covenant due within the lookahead horizon and a stale gate. The
     * required as-of ceiling is the run date: the gate stages up to that cut, and evaluation (which
     * re-gates at its own as-of) then finds it fresh and skips the source.
     *
     * @param runDate the sweep date (defaults to today in the system zone when {@code null})
     */
    public PreStageResult preStage(LocalDate runDate) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate date = runDate != null ? runDate : LocalDate.now(zone);
        LocalDate horizon = date.plusDays(properties.getLookaheadDays());

        // Coalesce all due-soon covenants' demand into one target per (table, entity) before fetching.
        Map<TargetKey, Target> targets = new LinkedHashMap<>();
        int pageNumber = 0;
        Page<AppliedCovenant> page;
        do {
            page = appliedRepo.findByStatus(AppliedCovenantStatus.ACTIVE,
                    PageRequest.of(pageNumber, ACTIVE_PAGE_SIZE, Sort.by("id")));
            Map<String, Instant> lastExecuted = latestExecutedAtFor(page.getContent());
            for (AppliedCovenant ac : page.getContent()) {
                MonitoringTiming timing = timingFor(ac, lastExecuted.get(ac.getId()), date, zone);
                if (dueWithinHorizon(timing, date, horizon)) {
                    collectTargets(ac, date, targets);
                }
            }
            pageNumber++;
        } while (page.hasNext());

        // Run the gate once per coalesced candidate; isolate a failing entity from the rest.
        int ingested = 0;
        int skipped = 0;
        int failed = 0;
        for (Target t : targets.values()) {
            try {
                if (ingestionOrchestration.ingestIfStale(t.table, t.entityKey, t.from, date)) {
                    ingested++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException ex) {
                failed++;
                String safe = (t.table.getId() + "/" + t.entityKey + ": " + ex.getMessage())
                        .replace('\n', '_').replace('\r', '_');
                LOG.warn("Pre-stage ingest failed for {}", safe);
            }
        }

        String summary = ("Pre-stage sweep for " + date + " complete: candidates=" + targets.size()
                + ", ingested=" + ingested + ", skipped=" + skipped + ", failed=" + failed)
                .replace('\n', '_').replace('\r', '_');
        LOG.info("{}", summary);
        return new PreStageResult(targets.size(), ingested, skipped, failed);
    }

    /**
     * Compile one due covenant's condition and fold each term's source demand into {@code targets},
     * coalescing by {@code (table, entity)} and keeping the earliest (widest) look-back. A covenant that
     * fails to compile (e.g. an unregistered measure) is logged and skipped so it cannot abort the sweep.
     */
    private void collectTargets(AppliedCovenant ac, LocalDate asOf, Map<TargetKey, Target> targets) {
        String condition = ac.getConditionExpression();
        if (condition == null || condition.isBlank()) {
            condition = ac.getCovenantMaster().getConditionExpression();
        }
        List<AggregationRequest> requests;
        try {
            CompiledFormula compiled = formulaEngine.compile(condition, measureCatalog.activeCatalog());
            requests = buildRequests(compiled);
        } catch (RuntimeException ex) {
            String safeId = ac.getId() == null ? "null"
                    : ac.getId().replace('\n', '_').replace('\r', '_');
            String safe = (ex.getMessage() == null ? "null" : ex.getMessage())
                    .replace('\n', '_').replace('\r', '_');
            LOG.warn("Pre-stage skipped covenant {} (condition not resolvable): {}", safeId, safe);
            return;
        }

        Optional<String> obligorId = Optional.empty();
        boolean obligorResolved = false;
        for (AggregationRequest request : requests) {
            DataSourceTable table = request.measure().getDataSourceTable();
            if (table == null) {
                continue;
            }
            String entityKey;
            if (table.getEntityKeyLevel() == TableEntityKeyLevel.OBLIGOR_ID) {
                if (!obligorResolved) {
                    obligorId = obligorMappingService.obligorFor(ac.getCpId());
                    obligorResolved = true;
                }
                if (obligorId.isEmpty()) {
                    continue; // No obligor recorded for this CP → nothing to stage at obligor grain.
                }
                entityKey = obligorId.get();
            } else {
                entityKey = ac.getCpId();
            }
            LocalDate from = fetchFrom(request, asOf);
            TargetKey key = new TargetKey(table.getId(), entityKey);
            Target existing = targets.get(key);
            if (existing == null) {
                targets.put(key, new Target(table, entityKey, from));
            } else if (from.isBefore(existing.from)) {
                existing.from = from; // widen the window to satisfy the deepest-reaching covenant
            }
        }
    }

    /** Map each formula term to a resolvable request, looking up the measure entity by code. */
    private List<AggregationRequest> buildRequests(CompiledFormula compiled) {
        Map<String, Measure> byCode = new LinkedHashMap<>();
        for (Measure measure : measureRepository.findByCodeIn(compiled.referencedMeasures())) {
            byCode.put(measure.getCode(), measure);
        }
        List<AggregationRequest> requests = new ArrayList<>();
        for (FormulaTerm term : compiled.terms()) {
            Measure measure = byCode.get(term.measure());
            if (measure == null) {
                throw new com.crisil.covenant.formula.FormulaException(
                        "Measure '" + term.measure() + "' is not registered");
            }
            requests.add(new AggregationRequest(
                    measure, term.aggregation(), term.window(),
                    term.sampling(), term.inclusion(), term.valueKey()));
        }
        return requests;
    }

    /**
     * The inclusive lower bound of source data one term needs (mirrors the JIT path in
     * {@code AssessmentService}): a point-in-time term needs only {@code asOf}; a windowed term needs
     * back to {@code window.startFrom(asOf)}, widened one extra unit for PERIODIC sampling so the
     * boundary reporting period is never starved.
     */
    private static LocalDate fetchFrom(AggregationRequest request, LocalDate asOf) {
        Window window = request.window();
        if (window == null) {
            return asOf;
        }
        LocalDate start = window.startFrom(asOf);
        if (request.sampling() == SamplingMode.PERIODIC) {
            start = window.unit().subtract(start, 1);
        }
        return start;
    }

    /** Due-soon = a past due date not yet assessed (OVERDUE) or a due date landing on/before the horizon. */
    private static boolean dueWithinHorizon(MonitoringTiming timing, LocalDate date, LocalDate horizon) {
        if (timing.status() == TimingStatus.OVERDUE) {
            return true;
        }
        LocalDate next = timing.nextDueDate();
        return next != null && !next.isBefore(date) && !next.isAfter(horizon);
    }

    private MonitoringTiming timingFor(AppliedCovenant ac, Instant lastAssessedAt, LocalDate runDate,
                                       ZoneId zone) {
        String cron = com.crisil.covenant.util.MonitoringTimingCalculator
                .effectiveCron(ac.getFrequency(), ac.getCronExpression());
        return com.crisil.covenant.util.MonitoringTimingCalculator.compute(
                cron, ac.getStartDate(), ac.getEndDate(), lastAssessedAt, runDate, zone);
    }

    /**
     * The last {@code executedAt} per applied covenant in {@code covenants}, resolved in one grouped
     * query. Covenants with no executed assessment are absent from the map (→ never assessed).
     */
    private Map<String, Instant> latestExecutedAtFor(List<AppliedCovenant> covenants) {
        if (covenants.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>(covenants.size());
        for (AppliedCovenant ac : covenants) {
            ids.add(ac.getId());
        }
        Map<String, Instant> lastExecuted = new HashMap<>();
        for (AppliedCovenantLastExecuted row
                : assessmentRepo.findLatestExecutedAtByAppliedCovenantIdIn(ids)) {
            if (row.getLastExecutedAt() != null) {
                lastExecuted.put(row.getAppliedCovenantId(), row.getLastExecutedAt());
            }
        }
        return lastExecuted;
    }

    /** Coalescing key: one extract per distinct source table + entity grain. */
    private record TargetKey(String tableId, String entityKey) {
    }

    /** Mutable accumulator for a coalesced extract; {@code from} widens as deeper covenants are folded in. */
    private static final class Target {
        private final DataSourceTable table;
        private final String entityKey;
        private LocalDate from;

        Target(DataSourceTable table, String entityKey, LocalDate from) {
            this.table = table;
            this.entityKey = entityKey;
            this.from = from;
        }
    }
}
