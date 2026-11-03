package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.formula.CompiledFormula;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaException;
import com.crisil.covenant.formula.FormulaTerm;
import com.crisil.covenant.formula.FormulaThreshold;
import com.crisil.covenant.formula.FormulaType;
import com.crisil.covenant.formula.FunctionKind;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.formula.PeriodInclusion;
import com.crisil.covenant.formula.SamplingMode;
import com.crisil.covenant.formula.Window;
import com.crisil.covenant.formula.WindowUnit;
import com.crisil.covenant.entity.AssessmentThreshold;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.service.MeasureAggregationResolver;
import com.crisil.covenant.service.MeasureAggregationResolver.MeasureValue;
import com.crisil.covenant.service.MeasureCatalogService;
import com.crisil.covenant.repository.MeasureRepository;
import com.crisil.covenant.service.ReportingSettingsService;
import com.crisil.covenant.dto.ReportingSettingsResponse;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentStatus;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.repository.AssessmentRepository;

@ExtendWith(MockitoExtension.class)
class AssessmentServiceTest {

    @Mock private AssessmentRepository assessmentRepo;
    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private FormulaEngine formulaEngine;
    @Mock private MeasureCatalogService measureCatalog;
    @Mock private MeasureRepository measureRepository;
    @Mock private MeasureAggregationResolver aggregationResolver;
    @Mock private IngestionOrchestrationService ingestionOrchestration;
    @Mock private ReportingSettingsService reportingSettings;
    @Mock private WorkflowCaseService workflowCaseService;
    @Mock private ObligorMappingService obligorMappingService;
    private AssessmentService service;

    private CompiledFormula compiled;

    @BeforeEach
    void stubCommon() {
        // A real binder over the mocked MeasureRepository: the extracted FormulaMeasureBinder does
        // exactly what AssessmentService.buildRequests used to, so the measureRepository stubs below
        // stay meaningful and behaviour is unchanged.
        service = new AssessmentService(assessmentRepo, appliedRepo, formulaEngine, measureCatalog,
                aggregationResolver, ingestionOrchestration, reportingSettings, workflowCaseService,
                obligorMappingService, new FormulaMeasureBinder(measureRepository));
        lenient().when(workflowCaseService.newRunContext()).thenReturn(new WorkflowCaseContext());
        lenient().when(workflowCaseService.ensureCase(anyString(), any()))
                .thenReturn(WorkflowCaseContext.CaseOutcome.ok("wf-1"));
        lenient().when(assessmentRepo.saveAndFlush(any(Assessment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(measureCatalog.activeCatalog()).thenReturn(mock(MeasureCatalog.class));
        lenient().when(reportingSettings.current()).thenReturn(ReportingSettingsResponse.defaults());
        lenient().when(formulaEngine.renderResolved(any(), any())).thenReturn("DSCR=1.85 > 1.2");

        compiled = mock(CompiledFormula.class);
        lenient().when(compiled.terms())
                .thenReturn(List.of(new FormulaTerm("DSCR", null, null, "DSCR")));
        lenient().when(compiled.referencedMeasures()).thenReturn(List.of("DSCR"));
        lenient().when(formulaEngine.compile(anyString(), any())).thenReturn(compiled);
        lenient().when(formulaEngine.trackedValueExpression(any())).thenReturn(Optional.of("DSCR"));
        lenient().when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR")));
    }

    @Test
    void conditionTrueProducesCompliant() {
        bindApplied();
        stubResolve(present(1.85));
        when(formulaEngine.evaluateRaw(any(), any())).thenReturn(1.85);
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        assertThat(result.getResult()).isEqualTo(AssessmentResult.COMPLIANT);
        assertThat(result.getStatus()).isEqualTo(AssessmentStatus.COMPLETED);
        assertThat(result.getComputedValue()).isEqualTo("1.85");
        assertThat(result.getComputedValueType()).isEqualTo("Double");
        assertThat(result.getEvaluatedExpression()).isEqualTo("DSCR=1.85 > 1.2");
        assertThat(result.getExecutedAt()).isNotNull();
        assertThat(result.getWorkflowInstanceId()).isEqualTo("wf-1");
    }

    @Test
    void usesPerCpOverrideConditionWhenPresent() {
        AppliedCovenant applied = bindApplied();
        applied.setConditionExpression("DSCR > 2");
        stubResolve(present(1.85));
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);

        service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        org.mockito.Mockito.verify(formulaEngine).compile(eq("DSCR > 2"), any());
    }

    @Test
    void conditionFalseProducesBreached() {
        bindApplied();
        stubResolve(present(1.0));
        when(formulaEngine.evaluateRaw(any(), any())).thenReturn(1.0);
        when(formulaEngine.evaluate(any(), any())).thenReturn(false);

        assertThat(service.runAssessment("1", AssessmentTrigger.SCHEDULED, null).getResult())
                .isEqualTo(AssessmentResult.BREACHED);
    }

    @Test
    void missingValueShortCircuitsToError() {
        bindApplied();
        stubResolve(Map.of("DSCR", new MeasureValue("DSCR", null, false)));

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);
        assertThat(result.getResult()).isEqualTo(AssessmentResult.ERROR);
        assertThat(result.getErrorMessage()).contains("DSCR");
    }

    @Test
    void compileErrorMarksError() {
        bindApplied();
        when(formulaEngine.compile(anyString(), any())).thenThrow(new FormulaException("syntax boom"));

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);
        assertThat(result.getResult()).isEqualTo(AssessmentResult.ERROR);
        assertThat(result.getErrorMessage()).contains("Formula error");
    }

    @Test
    void unregisteredMeasureMarksError() {
        bindApplied();
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of());

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);
        assertThat(result.getResult()).isEqualTo(AssessmentResult.ERROR);
        assertThat(result.getErrorMessage()).contains("not registered");
    }

    @Test
    void resolverFailureMarksError() {
        bindApplied();
        when(aggregationResolver.resolve(eq("CP-1"), any(LocalDate.class), anyList(),
                anyInt(), anyBoolean())).thenThrow(new RuntimeException("upstream"));

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);
        assertThat(result.getResult()).isEqualTo(AssessmentResult.ERROR);
        assertThat(result.getErrorMessage()).contains("DATA_SOURCE_FAILURE");
    }

    @Test
    void passesOrganisationReportingCalendarToResolver() {
        bindApplied();
        when(reportingSettings.current())
                .thenReturn(new ReportingSettingsResponse(4, false, 7, 30, true, "INR", null, null));
        when(aggregationResolver.resolve(eq("CP-1"), any(LocalDate.class), anyList(),
                eq(4), eq(false))).thenReturn(present(1.85));
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);

        service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        verify(aggregationResolver).resolve(eq("CP-1"), any(LocalDate.class), anyList(),
                eq(4), eq(false));
    }

    // --- Phase 2: JIT staging ingestion before resolve ---

    @Test
    void jitIngestsEachDistinctSourceTableOnceBeforeResolving() {
        bindApplied();
        DataSourceTable shared = tableWithId("tbl-1");
        // Two terms backed by the SAME table must trigger a single ingest for that table.
        when(measureRepository.findByCodeIn(any()))
                .thenReturn(List.of(measureOn("DSCR", shared), measureOn("ICR", shared)));
        when(compiled.terms()).thenReturn(List.of(
                new FormulaTerm("DSCR", null, null, "DSCR"),
                new FormulaTerm("ICR", null, null, "ICR")));
        when(compiled.referencedMeasures()).thenReturn(List.of("DSCR", "ICR"));
        stubResolve(Map.of(
                "DSCR", new MeasureValue("DSCR", 1.85, true),
                "ICR", new MeasureValue("ICR", 2.5, true)));
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        // De-duplicated to exactly one ingest for the shared table, at the CP grain.
        verify(ingestionOrchestration, times(1))
                .ingestIfStale(eq(shared), eq("CP-1"), any(LocalDate.class), any(LocalDate.class));
        // Ingestion must precede the resolver read; the snapshot is still written afterwards.
        InOrder inOrder = inOrder(ingestionOrchestration, aggregationResolver);
        inOrder.verify(ingestionOrchestration)
                .ingestIfStale(eq(shared), eq("CP-1"), any(LocalDate.class), any(LocalDate.class));
        inOrder.verify(aggregationResolver).resolve(eq("CP-1"), any(LocalDate.class), anyList(),
                anyInt(), anyBoolean());
        assertThat(result.getDataSnapshotJson()).contains("DSCR");
    }

    @Test
    void jitIngestsBothTablesWhenTermsSpanTwoSources() {
        bindApplied();
        DataSourceTable finTable = tableWithId("tbl-fin");
        DataSourceTable ratioTable = tableWithId("tbl-ratio");
        when(measureRepository.findByCodeIn(any()))
                .thenReturn(List.of(measureOn("DSCR", finTable), measureOn("ICR", ratioTable)));
        when(compiled.terms()).thenReturn(List.of(
                new FormulaTerm("DSCR", null, null, "DSCR"),
                new FormulaTerm("ICR", null, null, "ICR")));
        when(compiled.referencedMeasures()).thenReturn(List.of("DSCR", "ICR"));
        stubResolve(Map.of(
                "DSCR", new MeasureValue("DSCR", 1.85, true),
                "ICR", new MeasureValue("ICR", 2.5, true)));
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);

        service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        verify(ingestionOrchestration)
                .ingestIfStale(eq(finTable), eq("CP-1"), any(LocalDate.class), any(LocalDate.class));
        verify(ingestionOrchestration)
                .ingestIfStale(eq(ratioTable), eq("CP-1"), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void jitIngestsFromWidestWindowStartAcrossTermsOnOneTable() {
        bindApplied();
        DataSourceTable shared = tableWithId("tbl-1");
        // Two windowed terms on one table: a 2-year FLAT term and a 6-month one. Ingestion must cover
        // the widest look-back (the 2-year start) so both terms find their history in one staged copy.
        when(measureRepository.findByCodeIn(any()))
                .thenReturn(List.of(measureOn("PAT", shared), measureOn("REV", shared)));
        when(compiled.terms()).thenReturn(List.of(
                new FormulaTerm("PAT", FunctionKind.AVG, new Window(2, WindowUnit.Y),
                        SamplingMode.FLAT, PeriodInclusion.DEFAULT, "PAT"),
                new FormulaTerm("REV", FunctionKind.AVG, new Window(6, WindowUnit.M),
                        SamplingMode.FLAT, PeriodInclusion.DEFAULT, "REV")));
        when(compiled.referencedMeasures()).thenReturn(List.of("PAT", "REV"));
        stubResolve(Map.of(
                "PAT", new MeasureValue("PAT", 10.0, true),
                "REV", new MeasureValue("REV", 20.0, true)));
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);

        LocalDate before = LocalDate.now();
        service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);
        LocalDate after = LocalDate.now();

        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> asOfCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(ingestionOrchestration, times(1))
                .ingestIfStale(eq(shared), eq("CP-1"), fromCaptor.capture(), asOfCaptor.capture());
        LocalDate asOf = asOfCaptor.getValue();
        assertThat(asOf).isBetween(before, after);
        // Widest window wins: 2 years back from the as-of date, not 6 months.
        assertThat(fromCaptor.getValue()).isEqualTo(asOf.minusYears(2));
    }

    @Test
    void jitWidensPeriodicWindowByOneExtraPeriod() {
        bindApplied();
        DataSourceTable shared = tableWithId("tbl-1");
        // A PERIODIC term aligns to reporting-period ends, so ingestion widens one extra unit below the
        // raw window start to keep the boundary period from being starved of data.
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measureOn("PAT", shared)));
        when(compiled.terms()).thenReturn(List.of(
                new FormulaTerm("PAT", FunctionKind.AVG, new Window(3, WindowUnit.Y),
                        SamplingMode.PERIODIC, PeriodInclusion.DEFAULT, "PAT")));
        when(compiled.referencedMeasures()).thenReturn(List.of("PAT"));
        stubResolve(Map.of("PAT", new MeasureValue("PAT", 10.0, true)));
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);

        service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> asOfCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(ingestionOrchestration)
                .ingestIfStale(eq(shared), eq("CP-1"), fromCaptor.capture(), asOfCaptor.capture());
        // 3-year window + one extra year of PERIODIC head-room = 4 years back.
        assertThat(fromCaptor.getValue()).isEqualTo(asOfCaptor.getValue().minusYears(4));
    }

    @Test
    void ingestionFailureMarksDataSourceError() {
        bindApplied();
        when(measureRepository.findByCodeIn(any()))
                .thenReturn(List.of(measureOn("DSCR", tableWithId("tbl-1"))));
        doThrow(new RuntimeException("live source down"))
                .when(ingestionOrchestration)
                .ingestIfStale(any(), anyString(), any(LocalDate.class), any(LocalDate.class));

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        assertThat(result.getResult()).isEqualTo(AssessmentResult.ERROR);
        assertThat(result.getErrorMessage()).contains("DATA_SOURCE_FAILURE");
        verify(aggregationResolver, never())
                .resolve(anyString(), any(LocalDate.class), anyList(), anyInt(), anyBoolean());
    }

    // --- Phase 3: obligor-keyed JIT ingestion (fan-out grain) ---

    @Test
    void obligorKeyedTableIngestsAtObligorGrainButResolvesAndAttachesAtCpGrain() {
        bindApplied(); // applied "1" on CP-1
        DataSourceTable obligorTable = obligorTableWithId("tbl-obl");
        when(measureRepository.findByCodeIn(any()))
                .thenReturn(List.of(measureOn("DSCR", obligorTable)));
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.of("OBL-1"));
        stubResolve(present(1.85));
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        // Data is landed at the obligor grain (shared across the obligor's CPs), not the CP grain...
        verify(ingestionOrchestration)
                .ingestIfStale(eq(obligorTable), eq("OBL-1"), any(LocalDate.class), any(LocalDate.class));
        verify(ingestionOrchestration, never())
                .ingestIfStale(eq(obligorTable), eq("CP-1"), any(LocalDate.class), any(LocalDate.class));
        // ...while the covenant still resolves at the CP grain and the result attaches to that CP.
        verify(aggregationResolver).resolve(eq("CP-1"), any(LocalDate.class), anyList(),
                anyInt(), anyBoolean());
        assertThat(result.getAppliedCovenant().getCpId()).isEqualTo("CP-1");
    }

    @Test
    void obligorKeyedTableWithNoMappingSkipsIngestAndResolvesMissing() {
        bindApplied(); // CP-1 with no recorded obligor
        DataSourceTable obligorTable = obligorTableWithId("tbl-obl");
        when(measureRepository.findByCodeIn(any()))
                .thenReturn(List.of(measureOn("DSCR", obligorTable)));
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.empty());
        // Nothing landed at obligor grain ⇒ the resolver reports the term absent (not a wrong-CP read).
        stubResolve(Map.of("DSCR", new MeasureValue("DSCR", null, false)));

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        verify(ingestionOrchestration, never())
                .ingestIfStale(any(), anyString(), any(LocalDate.class), any(LocalDate.class));
        assertThat(result.getResult()).isEqualTo(AssessmentResult.ERROR);
        assertThat(result.getErrorMessage()).contains("DSCR");
    }

    @Test
    void evaluationErrorMarksError() {
        bindApplied();
        stubResolve(present(1.85));
        when(formulaEngine.evaluateRaw(any(), any())).thenReturn(1.85);
        when(formulaEngine.evaluate(any(), any())).thenThrow(new FormulaException("not boolean"));

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);
        assertThat(result.getResult()).isEqualTo(AssessmentResult.ERROR);
        assertThat(result.getErrorMessage()).contains("Evaluation error");
    }

    @Test
    void missingAppliedCovenantThrowsNotFound() {
        when(appliedRepo.findById("99")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.runAssessment("99", AssessmentTrigger.MANUAL, "u"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listForCpDelegates() {
        when(assessmentRepo.findByAppliedCovenantCpIdOrderByScheduledAtDesc("CP-1"))
                .thenReturn(List.of());
        assertThat(service.listForCp("CP-1")).isEmpty();
    }

    @Test
    void getReturnsAssessment() {
        Assessment a = new Assessment();
        when(assessmentRepo.findById("5")).thenReturn(Optional.of(a));
        assertThat(service.get("5")).isSameAs(a);
    }

    @Test
    void getMissingThrowsNotFound() {
        when(assessmentRepo.findById("5")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("5")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void workflowCaseErrorStampedAsWorkflowError() {
        bindApplied();
        stubResolve(present(1.85));
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);
        // The case coordinator memoises a failure as a CaseOutcome error; the assessment records it
        // and never blocks — the specific failure modes are covered in WorkflowCaseServiceTest.
        when(workflowCaseService.ensureCase(anyString(), any()))
                .thenReturn(WorkflowCaseContext.CaseOutcome.error("unreachable"));

        assertThat(service.runAssessment("1", AssessmentTrigger.SCHEDULED, null).getWorkflowError())
                .contains("unreachable");
    }

    @Test
    void rejectIfImmutableFieldChangingNoOpForNonCompleted() {
        Assessment original = new Assessment();
        original.setStatus(AssessmentStatus.IN_PROGRESS);
        Assessment proposed = new Assessment();
        proposed.setResult(AssessmentResult.BREACHED);
        AssessmentService.rejectIfImmutableFieldChanging(original, proposed);
    }

    @Test
    void rejectIfImmutableFieldChangingThrowsForCompleted() {
        Assessment original = new Assessment();
        original.setStatus(AssessmentStatus.COMPLETED);
        original.setResult(AssessmentResult.COMPLIANT);
        Assessment proposed = new Assessment();
        proposed.setStatus(AssessmentStatus.COMPLETED);
        proposed.setResult(AssessmentResult.BREACHED);
        assertThatThrownBy(() -> AssessmentService.rejectIfImmutableFieldChanging(original, proposed))
                .isInstanceOf(com.crisil.covenant.exception.ApiException.class);
    }

    // --- US-ASM-05: manual assessment execution ---

    @Test
    void manualCovenantCreatesPendingManualWithoutEvaluation() {
        bindManualApplied();

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        assertThat(result.getStatus()).isEqualTo(AssessmentStatus.PENDING_MANUAL);
        assertThat(result.getResult()).isEqualTo(AssessmentResult.PENDING_REVIEW);
        assertThat(result.getEvaluatedExpression()).isEqualTo("Confirm board resolution submitted");
        assertThat(result.getExecutedAt()).isNull();
        verify(formulaEngine, never()).compile(anyString(), any());
        // The workflow case is opened at assessment start now — even a PENDING_MANUAL row carries it.
        assertThat(result.getWorkflowInstanceId()).isEqualTo("wf-1");
    }

    @Test
    void completeManualRecordsOutcomeNoteUserAndTimestamp() {
        when(assessmentRepo.findById("5")).thenReturn(Optional.of(pendingManual()));

        Assessment result =
                service.completeManual("5", AssessmentResult.COMPLIANT, "  Verified board minutes  ", "alice");

        assertThat(result.getStatus()).isEqualTo(AssessmentStatus.COMPLETED);
        assertThat(result.getResult()).isEqualTo(AssessmentResult.COMPLIANT);
        assertThat(result.getAssessmentNote()).isEqualTo("Verified board minutes");
        assertThat(result.getTriggeredByUser()).isEqualTo("alice");
        assertThat(result.getExecutedAt()).isNotNull();
    }

    @Test
    void completeManualRequiresNote() {
        when(assessmentRepo.findById("5")).thenReturn(Optional.of(pendingManual()));
        assertThatThrownBy(() -> service.completeManual("5", AssessmentResult.COMPLIANT, "  ", "u"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("note is required");
    }

    @Test
    void completeManualRejectsNonOutcomeResult() {
        when(assessmentRepo.findById("5")).thenReturn(Optional.of(pendingManual()));
        assertThatThrownBy(() -> service.completeManual("5", AssessmentResult.ERROR, "note", "u"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("COMPLIANT");
    }

    @Test
    void completeManualRejectsWhenNotPendingManual() {
        Assessment completed = new Assessment();
        completed.setStatus(AssessmentStatus.COMPLETED);
        when(assessmentRepo.findById("5")).thenReturn(Optional.of(completed));
        assertThatThrownBy(() -> service.completeManual("5", AssessmentResult.COMPLIANT, "note", "u"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not awaiting manual completion");
    }

    @Test
    void completeManualMissingThrowsNotFound() {
        when(assessmentRepo.findById("5")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.completeManual("5", AssessmentResult.COMPLIANT, "note", "u"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- US-ASM-21: decomposed per-comparison values ---

    @Test
    void autoAssessmentPersistsOneRowPerComparison() {
        bindApplied();
        stubResolve(present(1.85));
        CompiledFormula dscr = mock(CompiledFormula.class);
        CompiledFormula icr = mock(CompiledFormula.class);
        when(formulaEngine.compile(eq("DSCR"), any())).thenReturn(dscr);
        when(formulaEngine.compile(eq("ICR"), any())).thenReturn(icr);
        when(formulaEngine.evaluateRaw(eq(dscr), any())).thenReturn(1.85);
        when(formulaEngine.evaluateRaw(eq(icr), any())).thenReturn(2.5);
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);
        when(formulaEngine.thresholds(anyString())).thenReturn(List.of(
                new FormulaThreshold(0, 0, FormulaType.NUMBER, "1.5", "DSCR", ">"),
                new FormulaThreshold(0, 0, FormulaType.NUMBER, "2", "ICR", ">")));

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        assertThat(result.getComparisons()).hasSize(2);
        AssessmentThreshold first = result.getComparisons().get(0);
        assertThat(first.getId()).isNull();
        assertThat(first.getAssessment()).isSameAs(result);
        assertThat(first.getOrdinal()).isZero();
        assertThat(first.getMeasure()).isEqualTo("DSCR");
        assertThat(first.getOperator()).isEqualTo(">");
        assertThat(first.getThresholdValue()).isEqualTo("1.5");
        assertThat(first.getActualValue()).isEqualTo("1.85");
        assertThat(first.getValueType()).isEqualTo(FormulaType.NUMBER);
        AssessmentThreshold second = result.getComparisons().get(1);
        assertThat(second.getOrdinal()).isEqualTo(1);
        assertThat(second.getMeasure()).isEqualTo("ICR");
        assertThat(second.getOperator()).isEqualTo(">");
        assertThat(second.getThresholdValue()).isEqualTo("2");
        assertThat(second.getActualValue()).isEqualTo("2.5");
        assertThat(second.getValueType()).isEqualTo(FormulaType.NUMBER);
    }

    @Test
    void singleComparisonActualEqualsComputedValue() {
        bindApplied();
        stubResolve(present(1.85));
        when(formulaEngine.evaluateRaw(any(), any())).thenReturn(1.85);
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);
        when(formulaEngine.thresholds(anyString())).thenReturn(List.of(
                new FormulaThreshold(0, 0, FormulaType.NUMBER, "1.25", "DSCR", ">=")));

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        assertThat(result.getComparisons()).hasSize(1);
        AssessmentThreshold only = result.getComparisons().get(0);
        assertThat(only.getMeasure()).isEqualTo("DSCR");
        assertThat(only.getOperator()).isEqualTo(">=");
        assertThat(only.getThresholdValue()).isEqualTo("1.25");
        assertThat(only.getActualValue()).isEqualTo(result.getComputedValue());
        assertThat(only.getValueType()).isEqualTo(FormulaType.NUMBER);
    }

    @Test
    void unresolvableComparisonActualStoredAsNull() {
        bindApplied();
        stubResolve(present(1.85));
        when(formulaEngine.evaluateRaw(any(), any())).thenReturn(1.85);
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);
        when(formulaEngine.compile(eq("ICR"), any()))
                .thenThrow(new FormulaException("cannot compile label"));
        when(formulaEngine.thresholds(anyString())).thenReturn(List.of(
                new FormulaThreshold(0, 0, FormulaType.NUMBER, "2", "ICR", ">")));

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        assertThat(result.getResult()).isEqualTo(AssessmentResult.COMPLIANT);
        assertThat(result.getComparisons()).hasSize(1);
        assertThat(result.getComparisons().get(0).getMeasure()).isEqualTo("ICR");
        assertThat(result.getComparisons().get(0).getActualValue()).isNull();
    }

    @Test
    void noComparisonRowsWhenExpressionHasNoThreshold() {
        bindApplied();
        stubResolve(present(1.85));
        when(formulaEngine.evaluate(any(), any())).thenReturn(true);
        when(formulaEngine.thresholds(anyString())).thenReturn(List.of());

        assertThat(service.runAssessment("1", AssessmentTrigger.SCHEDULED, null).getComparisons())
                .isEmpty();
    }

    @Test
    void manualAssessmentCreatesNoComparisonRows() {
        bindManualApplied();

        assertThat(service.runAssessment("1", AssessmentTrigger.SCHEDULED, null).getComparisons())
                .isEmpty();
    }

    @Test
    void erroredAssessmentCreatesNoComparisonRows() {
        bindApplied();
        when(formulaEngine.compile(anyString(), any())).thenThrow(new FormulaException("boom"));

        Assessment result = service.runAssessment("1", AssessmentTrigger.SCHEDULED, null);

        assertThat(result.getResult()).isEqualTo(AssessmentResult.ERROR);
        assertThat(result.getComparisons()).isEmpty();
    }

    private AppliedCovenant bindManualApplied() {
        CovenantMaster master = new CovenantMaster();
        setId(master, "8");
        master.setName("Board resolution");
        master.setCategory(CovenantCategory.NON_FINANCIAL);
        master.setAssessmentType(AssessmentType.MANUAL);
        master.setConditionExpression("Confirm board resolution submitted");
        master.setActive(true);

        AppliedCovenant a = new AppliedCovenant();
        setId(a, "1");
        a.setCpId("CP-1");
        a.setCovenantMaster(master);
        a.setFrequency(Frequency.MONTHLY);
        a.setCronExpression(Frequency.MONTHLY.defaultCron());
        a.setStartDate(LocalDate.now());
        a.setSnapshotJson("{}");
        when(appliedRepo.findById("1")).thenReturn(Optional.of(a));
        return a;
    }

    private static Assessment pendingManual() {
        CovenantMaster master = new CovenantMaster();
        master.setName("Board resolution");
        AppliedCovenant applied = new AppliedCovenant();
        applied.setCpId("CP-1");
        applied.setCovenantMaster(master);

        Assessment a = new Assessment();
        a.setAppliedCovenant(applied);
        a.setStatus(AssessmentStatus.PENDING_MANUAL);
        a.setResult(AssessmentResult.PENDING_REVIEW);
        return a;
    }

    private static Map<String, MeasureValue> present(Object value) {
        return Map.of("DSCR", new MeasureValue("DSCR", value, true));
    }

    private void stubResolve(Map<String, MeasureValue> values) {
        when(aggregationResolver.resolve(eq("CP-1"), any(LocalDate.class), anyList(),
                anyInt(), anyBoolean())).thenReturn(values);
    }

    private AppliedCovenant bindApplied() {
        CovenantMaster master = new CovenantMaster();
        setId(master, "7");
        master.setName("DSCR rule");
        master.setCategory(CovenantCategory.FINANCIAL);
        master.setConditionExpression("DSCR > 1.2");
        master.setReferencedMeasures(List.of("DSCR"));
        master.setBusinessSegments(List.of());
        master.setActive(true);

        AppliedCovenant a = new AppliedCovenant();
        setId(a, "1");
        a.setCpId("CP-1");
        a.setCovenantMaster(master);
        a.setFrequency(Frequency.DAILY);
        a.setCronExpression(Frequency.DAILY.defaultCron());
        a.setStartDate(LocalDate.now());
        a.setSnapshotJson("{}");
        when(appliedRepo.findById("1")).thenReturn(Optional.of(a));
        return a;
    }

    private static Measure measure(String code) {
        Measure m = new Measure();
        m.setCode(code);
        return m;
    }

    private static DataSourceTable tableWithId(String id) {
        DataSourceTable t = new DataSourceTable();
        setId(t, id);
        t.setLayout(TableLayout.COLUMNAR);
        return t;
    }

    private static DataSourceTable obligorTableWithId(String id) {
        DataSourceTable t = tableWithId(id);
        t.setEntityKeyLevel(TableEntityKeyLevel.OBLIGOR_ID);
        return t;
    }

    private static Measure measureOn(String code, DataSourceTable table) {
        Measure m = new Measure();
        m.setCode(code);
        m.setDataSourceTable(table);
        m.setSourceColumn(code.toLowerCase(java.util.Locale.ROOT));
        m.setDataType(DataColumnType.NUMBER);
        return m;
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
