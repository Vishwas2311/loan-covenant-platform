package com.crisil.covenant.service;

import com.crisil.covenant.dto.ops.AssessmentDiagnosticsResponse;
import com.crisil.covenant.dto.ops.CovenantDiagnostic;
import com.crisil.covenant.dto.ops.CpDiagnosticsResponse;
import com.crisil.covenant.dto.ops.IngestionStateView;
import com.crisil.covenant.dto.ops.StagingInspectResponse;
import com.crisil.covenant.dto.ops.StagingRowView;
import com.crisil.covenant.dto.ops.TermDiagnostic;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.IngestionState;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.entity.StagingValue;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.DiagnosticReason;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.formula.CompiledFormula;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaException;
import com.crisil.covenant.formula.FormulaTerm;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.repository.DataSourceTableRepository;
import com.crisil.covenant.repository.IngestionStateRepository;
import com.crisil.covenant.repository.StagingValueRepository;
import com.crisil.covenant.service.IngestionOrchestrationService.Decision;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-only production-support "why isn't it working" engine. For a CP (or a single assessment)
 * it re-walks the <em>exact</em> read path an assessment takes — compile the condition, bind terms to
 * measures ({@link FormulaMeasureBinder}), resolve each term's table / entity key
 * ({@link SupportEntityKeyResolver}) / item code exactly as {@link MeasureAggregationResolver} would,
 * probe the staging store and the freshness-gate control row — and classifies every term with a
 * {@link DiagnosticReason}. It writes nothing and re-ingests nothing; it only explains.
 *
 * <p>The classification that matters most reproduces the stale-ingestion-state incident: when nothing
 * is staged for the entity yet {@link IngestionOrchestrationService#decide} would still <em>skip</em>
 * re-ingest, the term is {@link DiagnosticReason#STAGING_EMPTY_INGEST_STATE_STALE} — the exact signal
 * that clearing {@code cov_ingestion_state} will fix the covenant.
 */
@Service
@Transactional(readOnly = true)
public class OpsDiagnosticsService {

    private final AppliedCovenantRepository appliedRepo;
    private final AssessmentRepository assessmentRepo;
    private final FormulaEngine formulaEngine;
    private final MeasureCatalogService measureCatalog;
    private final FormulaMeasureBinder measureBinder;
    private final SupportEntityKeyResolver entityKeyResolver;
    private final ObligorMappingService obligorMappingService;
    private final StagingValueRepository stagingRepo;
    private final IngestionStateRepository stateRepository;
    private final IngestionOrchestrationService ingestionOrchestration;
    private final DataSourceTableRepository tableRepository;

    public OpsDiagnosticsService(AppliedCovenantRepository appliedRepo,
                                 AssessmentRepository assessmentRepo,
                                 FormulaEngine formulaEngine,
                                 MeasureCatalogService measureCatalog,
                                 FormulaMeasureBinder measureBinder,
                                 SupportEntityKeyResolver entityKeyResolver,
                                 ObligorMappingService obligorMappingService,
                                 StagingValueRepository stagingRepo,
                                 IngestionStateRepository stateRepository,
                                 IngestionOrchestrationService ingestionOrchestration,
                                 DataSourceTableRepository tableRepository) {
        this.appliedRepo = appliedRepo;
        this.assessmentRepo = assessmentRepo;
        this.formulaEngine = formulaEngine;
        this.measureCatalog = measureCatalog;
        this.measureBinder = measureBinder;
        this.entityKeyResolver = entityKeyResolver;
        this.obligorMappingService = obligorMappingService;
        this.stagingRepo = stagingRepo;
        this.stateRepository = stateRepository;
        this.ingestionOrchestration = ingestionOrchestration;
        this.tableRepository = tableRepository;
    }

    /** Diagnose every covenant applied to a CP, term by term. */
    public CpDiagnosticsResponse diagnoseCp(String cpId) {
        List<AppliedCovenant> applied = appliedRepo.findByCpIdOrderByIdAsc(cpId);
        String obligorId = obligorMappingService.obligorFor(cpId).orElse(null);
        MeasureCatalog catalog = measureCatalog.activeCatalog();
        LocalDate asOf = LocalDate.now();
        Instant now = Instant.now();
        List<CovenantDiagnostic> covenants = new ArrayList<>(applied.size());
        for (AppliedCovenant ac : applied) {
            covenants.add(diagnoseCovenant(ac, cpId, catalog, asOf, now));
        }
        return new CpDiagnosticsResponse(cpId, obligorId, covenants.size(), covenants);
    }

    /** The stored outcome of one assessment plus a fresh re-diagnosis of its applied covenant. */
    public AssessmentDiagnosticsResponse diagnoseAssessment(String assessmentId) {
        Assessment assessment = assessmentRepo.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assessment " + assessmentId + " not found"));
        AppliedCovenant ac = assessment.getAppliedCovenant();
        String cpId = ac.getCpId();
        CovenantDiagnostic diagnostic = diagnoseCovenant(
                ac, cpId, measureCatalog.activeCatalog(), LocalDate.now(), Instant.now());
        return new AssessmentDiagnosticsResponse(
                assessment.getId(), ac.getId(), cpId,
                assessment.getResult() == null ? null : assessment.getResult().name(),
                assessment.getStatus() == null ? null : assessment.getStatus().name(),
                assessment.getErrorMessage(), assessment.getExecutedAt(), diagnostic);
    }

    /** The raw staged rows for one {@code (CP, table)} pair, read under the resolver's entity key. */
    public StagingInspectResponse inspectStaging(String cpId, String tableId) {
        DataSourceTable table = tableRepository.findById(tableId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Data source table " + tableId + " not found"));
        String entityKeyLevel = table.getEntityKeyLevel().name();
        Optional<String> entityKey = entityKeyResolver.entityKeyFor(table, cpId);
        if (entityKey.isEmpty()) {
            return new StagingInspectResponse(cpId, tableId, table.getName(), entityKeyLevel,
                    null, 0, List.of());
        }
        String entityKeyUsed = entityKey.get();
        List<StagingRowView> rows = stagingRepo
                .findByDataSourceTableIdAndEntityKey(tableId, entityKeyUsed).stream()
                .sorted(Comparator.comparing(StagingValue::getAsOfDate).reversed())
                .map(OpsDiagnosticsService::toRowView)
                .toList();
        return new StagingInspectResponse(cpId, tableId, table.getName(), entityKeyLevel,
                entityKeyUsed, rows.size(), rows);
    }

    /** The freshness-gate control rows (and current verdict) for every table the CP's covenants use. */
    public List<IngestionStateView> ingestionStates(String cpId) {
        MeasureCatalog catalog = measureCatalog.activeCatalog();
        Map<String, DataSourceTable> tables = new LinkedHashMap<>();
        for (AppliedCovenant ac : appliedRepo.findByCpIdOrderByIdAsc(cpId)) {
            CovenantMaster master = ac.getCovenantMaster();
            if (master.getAssessmentType() == AssessmentType.MANUAL) {
                continue;
            }
            CompiledFormula compiled = tryCompile(effectiveCondition(ac, master), catalog);
            if (compiled == null) {
                continue;
            }
            for (Measure measure : measureBinder.measuresByCode(compiled.referencedMeasures()).values()) {
                DataSourceTable table = measure.getDataSourceTable();
                if (table != null) {
                    tables.putIfAbsent(table.getId(), table);
                }
            }
        }
        LocalDate asOf = LocalDate.now();
        Instant now = Instant.now();
        List<IngestionStateView> views = new ArrayList<>(tables.size());
        for (DataSourceTable table : tables.values()) {
            views.add(ingestionStateView(table, cpId, asOf, now));
        }
        return views;
    }

    // -- internals -------------------------------------------------------

    private CovenantDiagnostic diagnoseCovenant(AppliedCovenant ac, String cpId, MeasureCatalog catalog,
                                                LocalDate asOf, Instant now) {
        CovenantMaster master = ac.getCovenantMaster();
        String condition = effectiveCondition(ac, master);
        String assessmentType = master.getAssessmentType() == null ? null
                : master.getAssessmentType().name();

        // A MANUAL covenant resolves no measures and evaluates no formula — its "condition" is
        // free-text instruction, so we must not try to compile it.
        if (master.getAssessmentType() == AssessmentType.MANUAL) {
            return new CovenantDiagnostic(ac.getId(), master.getId(), master.getName(),
                    assessmentType, condition, true, null, true, List.of());
        }

        CompiledFormula compiled;
        try {
            compiled = formulaEngine.compile(condition, catalog);
        } catch (FormulaException ex) {
            return new CovenantDiagnostic(ac.getId(), master.getId(), master.getName(),
                    assessmentType, condition, false, ex.getMessage(), false, List.of());
        }

        Map<String, Measure> byCode = measureBinder.measuresByCode(compiled.referencedMeasures());
        List<TermDiagnostic> terms = new ArrayList<>(compiled.terms().size());
        boolean wouldResolve = true;
        for (FormulaTerm term : compiled.terms()) {
            TermDiagnostic diagnostic = diagnoseTerm(term, byCode, cpId, asOf, now);
            terms.add(diagnostic);
            if (!diagnostic.present()) {
                wouldResolve = false;
            }
        }
        return new CovenantDiagnostic(ac.getId(), master.getId(), master.getName(),
                assessmentType, condition, true, null, wouldResolve, terms);
    }

    private TermDiagnostic diagnoseTerm(FormulaTerm term, Map<String, Measure> byCode,
                                        String cpId, LocalDate asOf, Instant now) {
        String measureCode = term.measure();
        String valueKey = term.valueKey();
        String aggregation = term.aggregation() == null ? null : term.aggregation().name();
        String windowText = term.window() == null ? null : term.window().canonical();

        Measure measure = byCode.get(measureCode);
        if (measure == null) {
            return new TermDiagnostic(measureCode, valueKey, aggregation, windowText,
                    null, null, null, null, null,
                    0L, 0L, null, null, null, null, 0L,
                    false, DiagnosticReason.MEASURE_NOT_REGISTERED,
                    "Measure '" + measureCode + "' has no cov_measure row.");
        }

        DataSourceTable table = measure.getDataSourceTable();
        String tableId = table.getId();
        String tableName = table.getName();
        String entityKeyLevel = table.getEntityKeyLevel().name();
        boolean keyValue = table.getLayout() == TableLayout.KEY_VALUE;
        String itemCode = keyValue ? measure.getSourceCode() : measure.getSourceColumn();

        Optional<String> entityKey = entityKeyResolver.entityKeyFor(table, cpId);
        if (entityKey.isEmpty()) {
            return new TermDiagnostic(measureCode, valueKey, aggregation, windowText,
                    tableId, tableName, entityKeyLevel, null, itemCode,
                    0L, 0L, null, null, null, null, 0L,
                    false, DiagnosticReason.NO_OBLIGOR_MAPPING,
                    "Table '" + tableName + "' is obligor-keyed but CP " + cpId
                            + " has no cov_obligor_cp_map row.");
        }
        String entityKeyUsed = entityKey.get();

        long entityRowCount = stagingRepo.countByDataSourceTableIdAndEntityKey(tableId, entityKeyUsed);
        List<StagingValue> itemRows = stagingRepo
                .findByDataSourceTableIdAndEntityKeyAndItemCode(tableId, entityKeyUsed, itemCode);
        long itemRowCount = itemRows.size();

        // Window bounds mirror the resolver's non-periodic path: lower = window.startFrom(asOf) (or
        // null when the term is point-in-time), upper = asOf. Diagnostics does not model PERIODIC
        // period-alignment; it only needs to see whether any observation lands inside the window.
        LocalDate windowLower = term.window() == null ? null : term.window().startFrom(asOf);
        LocalDate windowUpper = asOf;

        LocalDate minAsOf = null;
        LocalDate maxAsOf = null;
        long windowRowCount = 0L;
        for (StagingValue row : itemRows) {
            LocalDate date = row.getAsOfDate();
            if (minAsOf == null || date.isBefore(minAsOf)) {
                minAsOf = date;
            }
            if (maxAsOf == null || date.isAfter(maxAsOf)) {
                maxAsOf = date;
            }
            if (!date.isAfter(windowUpper) && (windowLower == null || !date.isBefore(windowLower))) {
                windowRowCount++;
            }
        }

        DiagnosticReason reason;
        String detail;
        if (itemRowCount == 0L) {
            if (entityRowCount == 0L) {
                IngestionState state = stateRepository
                        .findByDataSourceTableIdAndEntityKey(tableId, entityKeyUsed).orElse(null);
                Decision decision = ingestionOrchestration.decide(state, asOf, now);
                if (state != null && !decision.ingest()) {
                    reason = DiagnosticReason.STAGING_EMPTY_INGEST_STATE_STALE;
                    detail = "Nothing staged for the entity, yet the freshness gate would SKIP re-ingest "
                            + "(reason='" + decision.reason() + "'). Clear cov_ingestion_state for this "
                            + "entity so the next assessment re-ingests.";
                } else {
                    reason = DiagnosticReason.STAGING_EMPTY_NO_STATE;
                    detail = "Nothing staged for the entity; the freshness gate would re-ingest on the "
                            + "next run (reason='" + decision.reason() + "') — it will self-heal.";
                }
            } else {
                reason = DiagnosticReason.ITEM_CODE_NOT_STAGED;
                detail = "The entity has " + entityRowCount + " staged row(s) under this table, but none "
                        + "for item code '" + itemCode + "' — check the measure's source_column/"
                        + "source_code mapping.";
            }
        } else if (windowRowCount == 0L) {
            reason = DiagnosticReason.WINDOW_EMPTY;
            detail = itemRowCount + " row(s) staged for '" + itemCode + "' (as-of " + iso(minAsOf) + " .. "
                    + iso(maxAsOf) + "), but none fall inside the evaluation window "
                    + windowLabel(windowLower, windowUpper) + ".";
        } else {
            reason = DiagnosticReason.OK;
            detail = "Resolves: " + windowRowCount + " row(s) for '" + itemCode
                    + "' inside the evaluation window.";
        }

        return new TermDiagnostic(measureCode, valueKey, aggregation, windowText,
                tableId, tableName, entityKeyLevel, entityKeyUsed, itemCode,
                entityRowCount, itemRowCount, iso(minAsOf), iso(maxAsOf),
                iso(windowLower), iso(windowUpper), windowRowCount,
                reason == DiagnosticReason.OK, reason, detail);
    }

    private IngestionStateView ingestionStateView(DataSourceTable table, String cpId,
                                                  LocalDate asOf, Instant now) {
        String entityKeyLevel = table.getEntityKeyLevel().name();
        Optional<String> entityKey = entityKeyResolver.entityKeyFor(table, cpId);
        if (entityKey.isEmpty()) {
            return new IngestionStateView(table.getId(), table.getName(), entityKeyLevel, null,
                    false, null, null, null, null, null, 0L, false, "no-obligor-mapping");
        }
        String entityKeyUsed = entityKey.get();
        IngestionState state = stateRepository
                .findByDataSourceTableIdAndEntityKey(table.getId(), entityKeyUsed).orElse(null);
        long stagingRowCount = stagingRepo
                .countByDataSourceTableIdAndEntityKey(table.getId(), entityKeyUsed);
        Decision decision = ingestionOrchestration.decide(state, asOf, now);
        return new IngestionStateView(
                table.getId(), table.getName(), entityKeyLevel, entityKeyUsed,
                state != null,
                state == null || state.getStagedAsOf() == null ? null : state.getStagedAsOf().toString(),
                state == null ? null : state.getStagedIngestedAt(),
                state == null ? null : state.getSourceChangedAt(),
                state == null ? null : state.getNextEligibleAt(),
                state == null || state.getLastStatus() == null ? null : state.getLastStatus().name(),
                stagingRowCount, decision.ingest(), decision.reason());
    }

    /** The effective condition for a covenant: the per-CP override, falling back to the master's. */
    private static String effectiveCondition(AppliedCovenant ac, CovenantMaster master) {
        String condition = ac.getConditionExpression();
        if (condition == null || condition.isBlank()) {
            return master.getConditionExpression();
        }
        return condition;
    }

    /** Compile a condition, returning {@code null} instead of throwing when it does not parse. */
    private CompiledFormula tryCompile(String condition, MeasureCatalog catalog) {
        try {
            return formulaEngine.compile(condition, catalog);
        } catch (FormulaException ex) {
            return null;
        }
    }

    private static StagingRowView toRowView(StagingValue row) {
        return new StagingRowView(iso(row.getAsOfDate()), row.getItemCode(),
                row.getDataType().name(), value(row), row.getIngestedAt(), row.getIngestBatchId());
    }

    private static Object value(StagingValue row) {
        return switch (row.getDataType()) {
            case NUMBER -> row.getNumValue();
            case DATE -> row.getDateValue() == null ? null : row.getDateValue().toString();
            case BOOLEAN -> row.getBoolValue();
            case STRING -> row.getStrValue();
        };
    }

    private static String windowLabel(LocalDate lower, LocalDate upper) {
        if (lower == null) {
            return "(as of " + iso(upper) + " or earlier)";
        }
        return "[" + iso(lower) + " .. " + iso(upper) + "]";
    }

    private static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
