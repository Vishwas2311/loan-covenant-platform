package com.crisil.covenant.service;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.exception.SourceReadException;
import com.crisil.covenant.formula.CompiledFormula;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaException;
import com.crisil.covenant.formula.FormulaThreshold;
import com.crisil.covenant.formula.SamplingMode;
import com.crisil.covenant.formula.Window;
import com.crisil.covenant.entity.AssessmentThreshold;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.service.MeasureAggregationResolver;
import com.crisil.covenant.service.MeasureAggregationResolver.AggregationRequest;
import com.crisil.covenant.service.MeasureAggregationResolver.MeasureValue;
import com.crisil.covenant.service.MeasureCatalogService;
import com.crisil.covenant.service.ReportingSettingsService;
import com.crisil.covenant.dto.ReportingSettingsResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentStatus;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.repository.AssessmentRepository;

@Service
@Transactional
public class AssessmentService {

    private static final Logger LOG = LoggerFactory.getLogger(AssessmentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AssessmentRepository assessmentRepo;
    private final AppliedCovenantRepository appliedRepo;
    private final FormulaEngine formulaEngine;
    private final MeasureCatalogService measureCatalog;
    private final MeasureAggregationResolver aggregationResolver;
    private final IngestionOrchestrationService ingestionOrchestration;
    private final ReportingSettingsService reportingSettings;
    private final WorkflowCaseService workflowCaseService;
    private final ObligorMappingService obligorMappingService;
    private final FormulaMeasureBinder measureBinder;

    public AssessmentService(AssessmentRepository assessmentRepo,
                             AppliedCovenantRepository appliedRepo,
                             FormulaEngine formulaEngine,
                             MeasureCatalogService measureCatalog,
                             MeasureAggregationResolver aggregationResolver,
                             IngestionOrchestrationService ingestionOrchestration,
                             ReportingSettingsService reportingSettings,
                             WorkflowCaseService workflowCaseService,
                             ObligorMappingService obligorMappingService,
                             FormulaMeasureBinder measureBinder) {
        this.assessmentRepo = assessmentRepo;
        this.appliedRepo = appliedRepo;
        this.formulaEngine = formulaEngine;
        this.measureCatalog = measureCatalog;
        this.aggregationResolver = aggregationResolver;
        this.ingestionOrchestration = ingestionOrchestration;
        this.reportingSettings = reportingSettings;
        this.workflowCaseService = workflowCaseService;
        this.obligorMappingService = obligorMappingService;
        this.measureBinder = measureBinder;
    }

    /** A fresh workflow run context; a batch caller shares one across all the CPs it assesses. */
    public WorkflowCaseContext newRunContext() {
        return workflowCaseService.newRunContext();
    }

    /**
     * Assess one applied covenant as its own single-CP run: a fresh {@link WorkflowCaseContext} is
     * created so a lone Quartz fire or manual trigger opens exactly one workflow case for its CP.
     * Batch callers assessing many covenants should share one context via the 4-arg overload.
     */
    public Assessment runAssessment(String appliedCovenantId,
                                    AssessmentTrigger trigger,
                                    String triggeredByUser) {
        return runAssessment(appliedCovenantId, trigger, triggeredByUser, newRunContext());
    }

    /**
     * Assess one applied covenant within {@code ctx}. At start we ensure the CP's workflow case exists
     * (opened at most once per run, shared by all the CP's covenants) and stamp its {@code caseId}
     * onto this assessment row — every save path below then persists it.
     */
    public Assessment runAssessment(String appliedCovenantId,
                                    AssessmentTrigger trigger,
                                    String triggeredByUser,
                                    WorkflowCaseContext ctx) {
        AppliedCovenant applied = appliedRepo.findById(appliedCovenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Applied covenant " + appliedCovenantId + " not found"));

        // US-036: Assessment is append-only — each run creates a brand-new row.
        Assessment assessment = new Assessment();
        assessment.setAppliedCovenant(applied);
        assessment.setScheduledAt(Instant.now());
        assessment.setTriggeredBy(trigger);
        assessment.setTriggeredByUser(triggeredByUser);

        // Initiate the CP's workflow case at assessment start; stamp the caseId (or error) onto the
        // row now so every terminal state below persists it.
        WorkflowCaseContext.CaseOutcome caseOutcome = workflowCaseService.ensureCase(applied.getCpId(), ctx);
        assessment.setWorkflowInstanceId(caseOutcome.caseId());
        assessment.setWorkflowError(caseOutcome.error());

        CovenantMaster master = applied.getCovenantMaster();

        // US-ASM-05: a MANUAL covenant is assessed by a person — create a pending row carrying the
        // instruction text; no measures are resolved and no formula is evaluated.
        if (master.getAssessmentType() == AssessmentType.MANUAL) {
            assessment.setStatus(AssessmentStatus.PENDING_MANUAL);
            assessment.setResult(AssessmentResult.PENDING_REVIEW);
            assessment.setConditionExpression(master.getConditionExpression());
            assessment.setEvaluatedExpression(master.getConditionExpression());
            return assessmentRepo.saveAndFlush(assessment);
        }

        assessment.setStatus(AssessmentStatus.IN_PROGRESS);

        // Per-CP threshold override takes precedence over the master's condition. Snapshot the
        // definition on the assessment so it is preserved even if the covenant changes later.
        String condition = applied.getConditionExpression();
        if (condition == null || condition.isBlank()) {
            condition = master.getConditionExpression();
        }
        assessment.setConditionExpression(condition);
        assessmentRepo.saveAndFlush(assessment);

        CompiledFormula compiled;
        List<AggregationRequest> requests;
        try {
            compiled = formulaEngine.compile(condition, measureCatalog.activeCatalog());
            requests = measureBinder.bind(compiled);
        } catch (FormulaException ex) {
            return completeWithError(assessment, "Formula error: " + ex.getMessage());
        }

        Map<String, MeasureValue> resolved;
        List<String> staleWarnings;
        try {
            // US-AGG-11: PERIODIC terms need the organisation reporting calendar; FLAT terms ignore it.
            ReportingSettingsResponse settings = reportingSettings.current();
            LocalDate asOf = LocalDate.now();
            // Land a durable copy of the source data before evaluating so covenants run against the
            // stored mirror (cov_staging_value), not the live source.
            staleWarnings = ingestStaging(requests, applied.getCpId(), asOf);
            resolved = aggregationResolver.resolve(applied.getCpId(), asOf, requests,
                    settings.fiscalYearStartMonth(), settings.periodicIncludesCurrentPeriod());
        } catch (RuntimeException ex) {
            return completeWithError(assessment, "DATA_SOURCE_FAILURE: " + ex.getMessage());
        }

        Map<String, Object> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (AggregationRequest request : requests) {
            MeasureValue value = resolved.get(request.valueKey());
            if (value == null || !value.present()) {
                missing.add(request.measure().getCode());
            } else {
                values.put(request.valueKey(), value.value());
            }
        }
        assessment.setDataSnapshotJson(serialise(values));
        if (!staleWarnings.isEmpty()) {
            assessment.setAssessmentNote(String.join(" ", staleWarnings));
        }
        if (!missing.isEmpty()) {
            return completeWithError(assessment, "Missing values for measures: " + missing);
        }

        try {
            populateComputedValue(assessment, compiled, values);
            populateComparisons(assessment, condition, values);
            assessment.setEvaluatedExpression(formulaEngine.renderResolved(compiled, values));
            boolean satisfied = formulaEngine.evaluate(compiled, values);
            assessment.setResult(satisfied ? AssessmentResult.COMPLIANT : AssessmentResult.BREACHED);
        } catch (FormulaException ex) {
            return completeWithError(assessment, "Evaluation error: " + ex.getMessage());
        }

        assessment.setStatus(AssessmentStatus.COMPLETED);
        assessment.setExecutedAt(Instant.now());
        return assessmentRepo.saveAndFlush(assessment);
    }

    /**
     * JIT ingestion: land a durable copy of each distinct source table's data for this CP and as-of
     * date into {@code cov_staging_value} before the resolver reads it. Ingesting once per table (not
     * per term) keeps re-reads of the same table within one assessment from duplicating work; the
     * {@link IngestionOrchestrationService#ingestIfStale freshness gate} then skips the source fetch
     * entirely when the staged copy already satisfies demand.
     *
     * <p>An obligor-keyed table is ingested at the CP's obligor grain so the copy is shared across all
     * of that obligor's CPs; the CP's obligor is resolved at most once per assessment. A CP with no
     * recorded obligor has nothing to ingest at obligor grain — the resolver then reports those terms
     * as {@code present=false}.
     *
     * <p>When the source cannot be read but a stored copy exists, the assessment carries on with that
     * copy and the returned warnings say so; the resolver still refuses a value older than the
     * measure's max age, which then surfaces as missing. With no stored copy the failure is raised.
     */
    private List<String> ingestStaging(List<AggregationRequest> requests, String cpId, LocalDate asOf) {
        List<String> warnings = new ArrayList<>();
        Map<String, DataSourceTable> tables = new LinkedHashMap<>();
        Map<String, LocalDate> fromByTable = new LinkedHashMap<>();
        for (AggregationRequest request : requests) {
            DataSourceTable table = request.measure().getDataSourceTable();
            if (table == null) {
                continue;
            }
            tables.putIfAbsent(table.getId(), table);
            LocalDate from = fetchFrom(request, asOf);
            fromByTable.merge(table.getId(), from, (a, b) -> a.isBefore(b) ? a : b);
        }
        Optional<String> obligorId = Optional.empty();
        boolean obligorResolved = false;
        for (DataSourceTable table : tables.values()) {
            String entityKey;
            if (table.getEntityKeyLevel() == TableEntityKeyLevel.OBLIGOR_ID) {
                if (!obligorResolved) {
                    obligorId = obligorMappingService.obligorFor(cpId);
                    obligorResolved = true;
                }
                if (obligorId.isEmpty()) {
                    continue; // No obligor recorded for this CP → nothing to land at obligor grain.
                }
                entityKey = obligorId.get();
            } else {
                entityKey = cpId;
            }
            try {
                ingestionOrchestration.ingestIfStale(table, entityKey, fromByTable.get(table.getId()), asOf);
            } catch (SourceReadException ex) {
                Optional<Instant> storedAt = ingestionOrchestration.storedCopyAt(table, entityKey);
                if (storedAt.isEmpty()) {
                    throw ex;
                }
                // Inline CR/LF-sanitised detail so no tainted value reaches the sink.
                String safe = ("table " + table.getId() + ", stored copy from " + storedAt.get())
                        .replace('\n', '_').replace('\r', '_');
                LOG.warn("Refresh failed; using the stored copy: {}", safe);
                warnings.add("Data may be stale: the latest refresh of " + table.getName()
                        + " failed, so these figures come from the stored copy of " + storedAt.get() + ".");
            }
        }
        return warnings;
    }

    /**
     * US-DS-12: the inclusive lower bound of source data one term needs. A point-in-time term (no
     * window) needs only {@code asOf}; a windowed term needs back to {@code window.startFrom(asOf)}.
     * PERIODIC sampling aligns each period to its reporting-period end, so the earliest relevant
     * observation can fall one period before the raw window start — we widen by one extra unit so the
     * boundary period is never starved of data.
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

    /** Record the scalar the covenant tracks (the left side of the first comparison) for display. */
    private void populateComputedValue(Assessment assessment, CompiledFormula compiled,
                                       Map<String, Object> values) {
        formulaEngine.trackedValueExpression(compiled).ifPresent(expr -> {
            CompiledFormula tracked = formulaEngine.compile(expr, measureCatalog.activeCatalog());
            Object computed = formulaEngine.evaluateRaw(tracked, values);
            if (computed != null) {
                assessment.setComputedValue(formatComputed(computed));
                assessment.setComputedValueType(computed.getClass().getSimpleName());
            }
        });
    }

    /**
     * US-ASM-21: persist one append-only row per comparison in the assessed condition, capturing the
     * decomposed measure (left side), operator, threshold literal (right side) and the actual value —
     * the left side evaluated against the same resolved measure map used to decide the result. Reuses
     * {@link FormulaEngine#thresholds(String)} so the parse matches the rendered/evaluated expression.
     * No comparisons ⇒ no rows (AC5).
     */
    private void populateComparisons(Assessment assessment, String condition, Map<String, Object> values) {
        int ordinal = 0;
        for (FormulaThreshold threshold : formulaEngine.thresholds(condition)) {
            assessment.addComparison(new AssessmentThreshold(
                    ordinal++,
                    threshold.label(),
                    threshold.operator(),
                    threshold.displayValue(),
                    evaluateActual(threshold.label(), values),
                    threshold.valueType()));
        }
    }

    /** Evaluate a comparison's left side against the resolved values; null if it cannot be resolved. */
    private String evaluateActual(String measureExpression, Map<String, Object> values) {
        try {
            CompiledFormula compiled = formulaEngine.compile(measureExpression, measureCatalog.activeCatalog());
            return formatComputed(formulaEngine.evaluateRaw(compiled, values));
        } catch (FormulaException ex) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<Assessment> listForCp(String cpId) {
        return assessmentRepo.findByAppliedCovenantCpIdOrderByScheduledAtDesc(cpId);
    }

    @Transactional(readOnly = true)
    public Assessment get(String id) {
        return assessmentRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment " + id + " not found"));
    }

    /**
     * US-ASM-05: an analyst records the outcome of a {@code PENDING_MANUAL} assessment. The result
     * must be a real outcome (COMPLIANT / BREACHED / NA) and a note is mandatory. Completing the
     * pending row (not creating a new one) is allowed; once COMPLETED it is immutable (AC5).
     */
    public Assessment completeManual(String assessmentId, AssessmentResult result, String note,
                                     String completedByUser) {
        Assessment assessment = assessmentRepo.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assessment " + assessmentId + " not found"));

        if (assessment.getStatus() != AssessmentStatus.PENDING_MANUAL) {
            throw new ApiException(HttpStatus.CONFLICT, "ASSESSMENT_NOT_PENDING_MANUAL",
                    "Assessment " + assessmentId + " is not awaiting manual completion");
        }
        if (result != AssessmentResult.COMPLIANT
                && result != AssessmentResult.BREACHED
                && result != AssessmentResult.NA) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MANUAL_RESULT",
                    "Manual result must be COMPLIANT, BREACHED, or NA");
        }
        if (note == null || note.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MANUAL_NOTE_REQUIRED",
                    "A note is required to complete a manual assessment");
        }

        assessment.setResult(result);
        assessment.setAssessmentNote(note.trim());
        if (completedByUser != null && !completedByUser.isBlank()) {
            assessment.setTriggeredByUser(completedByUser);
        }
        assessment.setStatus(AssessmentStatus.COMPLETED);
        assessment.setExecutedAt(Instant.now());
        return assessmentRepo.saveAndFlush(assessment);
    }

    /**
     * US-036: callers that try to mutate a {@link AssessmentStatus#COMPLETED} assessment's
     * result / snapshot / evaluated expression are rejected.
     */
    public static void rejectIfImmutableFieldChanging(Assessment original, Assessment proposed) {
        if (original.getStatus() != AssessmentStatus.COMPLETED) {
            return;
        }
        if (notEqual(original.getResult(), proposed.getResult())
                || notEqual(original.getDataSnapshotJson(), proposed.getDataSnapshotJson())
                || notEqual(original.getEvaluatedExpression(), proposed.getEvaluatedExpression())
                || notEqual(original.getExecutedAt(), proposed.getExecutedAt())) {
            throw new com.crisil.covenant.exception.ApiException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "ASSESSMENT_IMMUTABLE",
                    "Cannot modify result, snapshot, evaluatedExpression, or executedAt of a COMPLETED assessment");
        }
    }

    private static boolean notEqual(Object a, Object b) {
        return a == null ? b != null : !a.equals(b);
    }

    private Assessment completeWithError(Assessment a, String message) {
        String safeId = a.getId() == null ? "null"
                : a.getId().replace('\n', '_').replace('\r', '_');
        LOG.warn("Assessment {} marked ERROR: {}", safeId, sanitise(message));
        a.setResult(AssessmentResult.ERROR);
        a.setErrorMessage(message);
        a.setStatus(AssessmentStatus.COMPLETED);
        a.setExecutedAt(Instant.now());
        return assessmentRepo.saveAndFlush(a);
    }

    /** Render the computed value as stable text (dates ISO, others via toString). */
    static String formatComputed(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d.toString();
        }
        return value.toString();
    }

    private static String serialise(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String sanitise(String value) {
        return value == null ? "null" : value.replace('\n', '_').replace('\r', '_');
    }
}
