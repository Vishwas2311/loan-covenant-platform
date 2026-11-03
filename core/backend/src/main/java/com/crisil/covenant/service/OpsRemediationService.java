package com.crisil.covenant.service;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.ops.RemediationResult;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.formula.CompiledFormula;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaException;
import com.crisil.covenant.formula.SamplingMode;
import com.crisil.covenant.formula.Window;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.IngestionStateRepository;
import com.crisil.covenant.service.MeasureAggregationResolver.AggregationRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Safe, non-destructive production-support remediation. Each action fixes a stuck covenant without
 * deleting business data and writes an audit row via {@link OpsAuditService}:
 * <ul>
 *   <li>{@link #reingestCp} force-refreshes then re-ingests every source table the CP's covenants use
 *       (obligor-aware; a table with no obligor mapping is skipped with a warning);</li>
 *   <li>{@link #clearIngestionState} deletes the {@code cov_ingestion_state} control row(s) so the next
 *       assessment re-ingests — the direct fix for the stale-state incident;</li>
 *   <li>{@link #reassess} re-runs one applied covenant's assessment ({@code MANUAL} trigger).</li>
 * </ul>
 * Tables and their fetch-from bounds are resolved through the very same seams evaluation uses
 * ({@link FormulaMeasureBinder}, {@link SupportEntityKeyResolver}) so remediation can never target a
 * table or entity key the assessment would not.
 */
@Service
public class OpsRemediationService {

    private static final String ACTION_REINGEST = "REINGEST_CP";
    private static final String ACTION_CLEAR_STATE = "CLEAR_INGESTION_STATE";
    private static final String ACTION_REASSESS = "REASSESS";
    private static final String TARGET_CP = "CP";
    private static final String TARGET_APPLIED = "APPLIED_COVENANT";

    private final AppliedCovenantRepository appliedRepo;
    private final FormulaEngine formulaEngine;
    private final MeasureCatalogService measureCatalog;
    private final FormulaMeasureBinder measureBinder;
    private final SupportEntityKeyResolver entityKeyResolver;
    private final IngestionOrchestrationService ingestionOrchestration;
    private final IngestionStateRepository stateRepository;
    private final AssessmentService assessmentService;
    private final OpsAuditService auditService;

    public OpsRemediationService(AppliedCovenantRepository appliedRepo,
                                 FormulaEngine formulaEngine,
                                 MeasureCatalogService measureCatalog,
                                 FormulaMeasureBinder measureBinder,
                                 SupportEntityKeyResolver entityKeyResolver,
                                 IngestionOrchestrationService ingestionOrchestration,
                                 IngestionStateRepository stateRepository,
                                 AssessmentService assessmentService,
                                 OpsAuditService auditService) {
        this.appliedRepo = appliedRepo;
        this.formulaEngine = formulaEngine;
        this.measureCatalog = measureCatalog;
        this.measureBinder = measureBinder;
        this.entityKeyResolver = entityKeyResolver;
        this.ingestionOrchestration = ingestionOrchestration;
        this.stateRepository = stateRepository;
        this.assessmentService = assessmentService;
        this.auditService = auditService;
    }

    /**
     * Force-refresh then re-ingest the CP's source tables. When {@code tableId} is blank every distinct
     * table the CP's covenants use is re-ingested; otherwise the action is scoped to that one table.
     */
    @Transactional
    public RemediationResult reingestCp(String cpId, String tableId) {
        List<String> warnings = new ArrayList<>();
        try {
            LocalDate asOf = LocalDate.now();
            Map<String, TableTarget> targets = collectTargets(cpId, tableId, asOf, warnings);
            List<String> touched = new ArrayList<>();
            boolean ingested = false;
            for (TableTarget target : targets.values()) {
                DataSourceTable table = target.table();
                Optional<String> entityKey = entityKeyResolver.entityKeyFor(table, cpId);
                if (entityKey.isEmpty()) {
                    warnings.add("Table '" + table.getName() + "' is obligor-keyed but CP " + cpId
                            + " has no obligor mapping — skipped.");
                    continue;
                }
                ingestionOrchestration.forceRefresh(table, entityKey.get());
                boolean did = ingestionOrchestration.ingestIfStale(
                        table, entityKey.get(), target.from(), asOf);
                ingested = ingested || did;
                touched.add(table.getId());
            }
            auditService.recordSuccess(ACTION_REINGEST, TARGET_CP, cpId, "tableId=" + tableId,
                    "tablesTouched=" + touched + ", ingested=" + ingested);
            return new RemediationResult("reingest", cpId, null, touched, ingested, null, warnings);
        } catch (RuntimeException ex) {
            auditService.recordFailure(ACTION_REINGEST, TARGET_CP, cpId, "tableId=" + tableId,
                    ex.getMessage());
            throw ex;
        }
    }

    /**
     * Delete the freshness-gate control row(s) for the CP so the next assessment re-ingests. This is the
     * fix for the stale-{@code cov_ingestion_state}-with-empty-staging incident.
     */
    @Transactional
    public RemediationResult clearIngestionState(String cpId, String tableId) {
        List<String> warnings = new ArrayList<>();
        try {
            LocalDate asOf = LocalDate.now();
            Map<String, TableTarget> targets = collectTargets(cpId, tableId, asOf, warnings);
            List<String> touched = new ArrayList<>();
            for (TableTarget target : targets.values()) {
                DataSourceTable table = target.table();
                Optional<String> entityKey = entityKeyResolver.entityKeyFor(table, cpId);
                if (entityKey.isEmpty()) {
                    warnings.add("Table '" + table.getName() + "' is obligor-keyed but CP " + cpId
                            + " has no obligor mapping — skipped.");
                    continue;
                }
                long deleted = stateRepository.deleteByDataSourceTableIdAndEntityKey(
                        table.getId(), entityKey.get());
                if (deleted > 0) {
                    touched.add(table.getId());
                } else {
                    warnings.add("No ingestion-state row for table '" + table.getName()
                            + "' — nothing to clear.");
                }
            }
            auditService.recordSuccess(ACTION_CLEAR_STATE, TARGET_CP, cpId, "tableId=" + tableId,
                    "cleared=" + touched);
            return new RemediationResult("clear-ingestion-state", cpId, null, touched, false, null,
                    warnings);
        } catch (RuntimeException ex) {
            auditService.recordFailure(ACTION_CLEAR_STATE, TARGET_CP, cpId, "tableId=" + tableId,
                    ex.getMessage());
            throw ex;
        }
    }

    /** Re-run one applied covenant's assessment with a {@code MANUAL} trigger. */
    public RemediationResult reassess(String appliedCovenantId) {
        String user = CurrentUserContext.currentUser().orElse(null);
        try {
            Assessment assessment = assessmentService.runAssessment(
                    appliedCovenantId, AssessmentTrigger.MANUAL, user);
            String cpId = assessment.getAppliedCovenant().getCpId();
            String result = assessment.getResult() == null ? null : assessment.getResult().name();
            auditService.recordSuccess(ACTION_REASSESS, TARGET_APPLIED, appliedCovenantId, null,
                    "result=" + result);
            return new RemediationResult("reassess", cpId, appliedCovenantId, List.of(), false,
                    result, List.of());
        } catch (RuntimeException ex) {
            auditService.recordFailure(ACTION_REASSESS, TARGET_APPLIED, appliedCovenantId, null,
                    ex.getMessage());
            throw ex;
        }
    }

    // -- internals -------------------------------------------------------

    /**
     * The distinct source tables the CP's covenants reference, each with the earliest fetch-from bound
     * across its terms (mirroring {@code AssessmentService.ingestStaging}). MANUAL, uncompilable, and
     * unregistered-measure covenants are skipped with a warning. When {@code tableId} is given the map
     * is filtered to that one table (warning when it is not used by any covenant).
     */
    private Map<String, TableTarget> collectTargets(String cpId, String tableId, LocalDate asOf,
                                                    List<String> warnings) {
        var catalog = measureCatalog.activeCatalog();
        Map<String, TableTarget> targets = new LinkedHashMap<>();
        for (AppliedCovenant ac : appliedRepo.findByCpIdOrderByIdAsc(cpId)) {
            CovenantMaster master = ac.getCovenantMaster();
            if (master.getAssessmentType() == AssessmentType.MANUAL) {
                continue;
            }
            CompiledFormula compiled;
            try {
                compiled = formulaEngine.compile(effectiveCondition(ac, master), catalog);
            } catch (FormulaException ex) {
                warnings.add("Covenant '" + master.getName() + "' (" + ac.getId()
                        + ") did not compile — skipped: " + ex.getMessage());
                continue;
            }
            List<AggregationRequest> requests;
            try {
                requests = measureBinder.bind(compiled);
            } catch (FormulaException ex) {
                warnings.add("Covenant '" + master.getName() + "' (" + ac.getId()
                        + ") references an unregistered measure — skipped: " + ex.getMessage());
                continue;
            }
            for (AggregationRequest request : requests) {
                DataSourceTable table = request.measure().getDataSourceTable();
                if (table == null) {
                    continue;
                }
                LocalDate from = fetchFrom(request, asOf);
                targets.merge(table.getId(), new TableTarget(table, from), (a, b) ->
                        new TableTarget(a.table(), a.from().isBefore(b.from()) ? a.from() : b.from()));
            }
        }
        if (tableId != null && !tableId.isBlank()) {
            Map<String, TableTarget> filtered = new LinkedHashMap<>();
            TableTarget only = targets.get(tableId);
            if (only != null) {
                filtered.put(tableId, only);
            } else {
                warnings.add("Table '" + tableId + "' is not used by any of CP " + cpId
                        + "'s covenants — nothing to do.");
            }
            return filtered;
        }
        return targets;
    }

    /** The effective condition for a covenant: the per-CP override, falling back to the master's. */
    private static String effectiveCondition(AppliedCovenant ac, CovenantMaster master) {
        String condition = ac.getConditionExpression();
        if (condition == null || condition.isBlank()) {
            return master.getConditionExpression();
        }
        return condition;
    }

    /** The earliest as-of date a term needs, mirroring {@code AssessmentService.fetchFrom}. */
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

    private record TableTarget(DataSourceTable table, LocalDate from) {
    }
}
