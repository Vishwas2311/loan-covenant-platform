package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

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
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentStatus;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.DiagnosticReason;
import com.crisil.covenant.enums.IngestStatus;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaType;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.formula.MeasureMeta;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.repository.DataSourceTableRepository;
import com.crisil.covenant.repository.IngestionStateRepository;
import com.crisil.covenant.repository.StagingValueRepository;
import com.crisil.covenant.service.IngestionOrchestrationService.Decision;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the read-only diagnostics engine. A <em>real</em> {@link FormulaEngine} compiles and
 * collects terms (so the trace mirrors the true read path), while the staging store, freshness gate,
 * measure binder and entity-key resolver are mocked to drive one branch per {@link DiagnosticReason}.
 */
@ExtendWith(MockitoExtension.class)
class OpsDiagnosticsServiceTest {

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private AssessmentRepository assessmentRepo;
    @Mock private MeasureCatalogService measureCatalog;
    @Mock private FormulaMeasureBinder measureBinder;
    @Mock private SupportEntityKeyResolver entityKeyResolver;
    @Mock private ObligorMappingService obligorMappingService;
    @Mock private StagingValueRepository stagingRepo;
    @Mock private IngestionStateRepository stateRepository;
    @Mock private IngestionOrchestrationService ingestionOrchestration;
    @Mock private DataSourceTableRepository tableRepository;

    private final FormulaEngine formulaEngine = new FormulaEngine();
    private OpsDiagnosticsService service;

    @BeforeEach
    void setUp() {
        service = new OpsDiagnosticsService(appliedRepo, assessmentRepo, formulaEngine, measureCatalog,
                measureBinder, entityKeyResolver, obligorMappingService, stagingRepo, stateRepository,
                ingestionOrchestration, tableRepository);
        // Every diagnose/ingestion-state path snapshots the active catalog; inspectStaging does not.
        lenient().when(measureCatalog.activeCatalog()).thenReturn(catalog());
    }

    // -- diagnoseCp: one case per DiagnosticReason ----------------------

    @Test
    void okWhenItemStagedInsideWindow() {
        DataSourceTable tbl = cpTable("tbl-fin", "Financials");
        Measure dscr = measure("DSCR", tbl, "dscr");
        bindCp(master("cm1", "DSCR rule", "DSCR > 1.2", AssessmentType.AUTO), null);
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.empty());
        when(measureBinder.measuresByCode(any())).thenReturn(Map.of("DSCR", dscr));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stagingRepo.countByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1")).thenReturn(1L);
        when(stagingRepo.findByDataSourceTableIdAndEntityKeyAndItemCode("tbl-fin", "CP-1", "dscr"))
                .thenReturn(List.of(num(tbl, "CP-1", LocalDate.now(), "dscr", "1.85")));

        CpDiagnosticsResponse resp = service.diagnoseCp("CP-1");

        assertThat(resp.cpId()).isEqualTo("CP-1");
        assertThat(resp.obligorId()).isNull();
        assertThat(resp.covenantCount()).isEqualTo(1);
        CovenantDiagnostic cov = resp.covenants().get(0);
        assertThat(cov.compiled()).isTrue();
        assertThat(cov.compileError()).isNull();
        assertThat(cov.wouldResolve()).isTrue();
        assertThat(cov.assessmentType()).isEqualTo("AUTO");
        TermDiagnostic term = cov.terms().get(0);
        assertThat(term.reason()).isEqualTo(DiagnosticReason.OK);
        assertThat(term.present()).isTrue();
        assertThat(term.measureCode()).isEqualTo("DSCR");
        assertThat(term.entityKeyUsed()).isEqualTo("CP-1");
        assertThat(term.entityKeyLevel()).isEqualTo("CP_ID");
        assertThat(term.itemCode()).isEqualTo("dscr");
        assertThat(term.aggregation()).isNull();
        assertThat(term.window()).isNull();
        assertThat(term.entityRowCount()).isEqualTo(1);
        assertThat(term.stagingRowCount()).isEqualTo(1);
        assertThat(term.windowRowCount()).isEqualTo(1);
    }

    @Test
    void windowEmptyWhenAggregationRowsFallOutsideWindow() {
        DataSourceTable tbl = cpTable("tbl-fin", "Financials");
        Measure pat = measure("PAT", tbl, "pat");
        bindCp(master("cm1", "PAT rule", "AVG(PAT, '1y') > 1", AssessmentType.AUTO), null);
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.empty());
        when(measureBinder.measuresByCode(any())).thenReturn(Map.of("PAT", pat));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stagingRepo.countByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1")).thenReturn(1L);
        when(stagingRepo.findByDataSourceTableIdAndEntityKeyAndItemCode("tbl-fin", "CP-1", "pat"))
                .thenReturn(List.of(num(tbl, "CP-1", LocalDate.now().minusYears(3), "pat", "5")));

        TermDiagnostic term = firstTerm(service.diagnoseCp("CP-1"));

        assertThat(term.reason()).isEqualTo(DiagnosticReason.WINDOW_EMPTY);
        assertThat(term.present()).isFalse();
        assertThat(term.aggregation()).isEqualTo("AVG");
        assertThat(term.window()).isEqualTo("1y");
        assertThat(term.windowLower()).isNotNull();
        assertThat(term.stagingRowCount()).isEqualTo(1);
        assertThat(term.windowRowCount()).isZero();
        assertThat(term.detail()).contains("[");
    }

    @Test
    void windowEmptyForBareMeasureWithOnlyFutureDatedRows() {
        DataSourceTable tbl = cpTable("tbl-fin", "Financials");
        Measure dscr = measure("DSCR", tbl, "dscr");
        bindCp(master("cm1", "DSCR rule", "DSCR > 1", AssessmentType.AUTO), null);
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.empty());
        when(measureBinder.measuresByCode(any())).thenReturn(Map.of("DSCR", dscr));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stagingRepo.countByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1")).thenReturn(1L);
        when(stagingRepo.findByDataSourceTableIdAndEntityKeyAndItemCode("tbl-fin", "CP-1", "dscr"))
                .thenReturn(List.of(num(tbl, "CP-1", LocalDate.now().plusDays(1), "dscr", "1.85")));

        TermDiagnostic term = firstTerm(service.diagnoseCp("CP-1"));

        assertThat(term.reason()).isEqualTo(DiagnosticReason.WINDOW_EMPTY);
        assertThat(term.window()).isNull();
        assertThat(term.windowLower()).isNull();
        assertThat(term.detail()).contains("or earlier");
    }

    @Test
    void itemCodeNotStagedWhenEntityHasRowsButNoneForItemCode() {
        DataSourceTable tbl = cpTable("tbl-fin", "Financials");
        Measure dscr = measure("DSCR", tbl, "dscr");
        bindCp(master("cm1", "DSCR rule", "DSCR > 1.2", AssessmentType.AUTO), null);
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.empty());
        when(measureBinder.measuresByCode(any())).thenReturn(Map.of("DSCR", dscr));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stagingRepo.countByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1")).thenReturn(3L);
        when(stagingRepo.findByDataSourceTableIdAndEntityKeyAndItemCode("tbl-fin", "CP-1", "dscr"))
                .thenReturn(List.of());

        TermDiagnostic term = firstTerm(service.diagnoseCp("CP-1"));

        assertThat(term.reason()).isEqualTo(DiagnosticReason.ITEM_CODE_NOT_STAGED);
        assertThat(term.present()).isFalse();
        assertThat(term.entityRowCount()).isEqualTo(3);
        assertThat(term.stagingRowCount()).isZero();
        assertThat(term.detail()).contains("dscr");
    }

    @Test
    void stagingEmptyIngestStateStaleIsTheBugSignal() {
        DataSourceTable tbl = cpTable("tbl-fin", "Financials");
        Measure dscr = measure("DSCR", tbl, "dscr");
        bindCp(master("cm1", "DSCR rule", "DSCR > 1.2", AssessmentType.AUTO), null);
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.empty());
        when(measureBinder.measuresByCode(any())).thenReturn(Map.of("DSCR", dscr));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stagingRepo.countByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1")).thenReturn(0L);
        when(stagingRepo.findByDataSourceTableIdAndEntityKeyAndItemCode("tbl-fin", "CP-1", "dscr"))
                .thenReturn(List.of());
        IngestionState state = new IngestionState();
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1"))
                .thenReturn(Optional.of(state));
        when(ingestionOrchestration.decide(any(), any(), any())).thenReturn(new Decision(false, "fresh"));

        TermDiagnostic term = firstTerm(service.diagnoseCp("CP-1"));

        assertThat(term.reason()).isEqualTo(DiagnosticReason.STAGING_EMPTY_INGEST_STATE_STALE);
        assertThat(term.present()).isFalse();
        assertThat(term.detail()).contains("fresh").contains("Clear cov_ingestion_state");
    }

    @Test
    void stagingEmptyNoStateSelfHealsWhenNoControlRow() {
        DataSourceTable tbl = cpTable("tbl-fin", "Financials");
        Measure dscr = measure("DSCR", tbl, "dscr");
        bindCp(master("cm1", "DSCR rule", "DSCR > 1.2", AssessmentType.AUTO), null);
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.empty());
        when(measureBinder.measuresByCode(any())).thenReturn(Map.of("DSCR", dscr));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stagingRepo.countByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1")).thenReturn(0L);
        when(stagingRepo.findByDataSourceTableIdAndEntityKeyAndItemCode("tbl-fin", "CP-1", "dscr"))
                .thenReturn(List.of());
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1"))
                .thenReturn(Optional.empty());
        when(ingestionOrchestration.decide(any(), any(), any())).thenReturn(new Decision(true, "missing"));

        TermDiagnostic term = firstTerm(service.diagnoseCp("CP-1"));

        assertThat(term.reason()).isEqualTo(DiagnosticReason.STAGING_EMPTY_NO_STATE);
        assertThat(term.detail()).contains("self-heal");
    }

    @Test
    void stagingEmptyNoStateWhenGateWouldStillIngestDespiteControlRow() {
        DataSourceTable tbl = cpTable("tbl-fin", "Financials");
        Measure dscr = measure("DSCR", tbl, "dscr");
        bindCp(master("cm1", "DSCR rule", "DSCR > 1.2", AssessmentType.AUTO), null);
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.empty());
        when(measureBinder.measuresByCode(any())).thenReturn(Map.of("DSCR", dscr));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stagingRepo.countByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1")).thenReturn(0L);
        when(stagingRepo.findByDataSourceTableIdAndEntityKeyAndItemCode("tbl-fin", "CP-1", "dscr"))
                .thenReturn(List.of());
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1"))
                .thenReturn(Optional.of(new IngestionState()));
        // Control row present, but the gate would re-ingest anyway ⇒ not the stale-state bug.
        when(ingestionOrchestration.decide(any(), any(), any())).thenReturn(new Decision(true, "stale"));

        TermDiagnostic term = firstTerm(service.diagnoseCp("CP-1"));

        assertThat(term.reason()).isEqualTo(DiagnosticReason.STAGING_EMPTY_NO_STATE);
    }

    @Test
    void noObligorMappingWhenObligorKeyedTableHasNoMap() {
        DataSourceTable tbl = table("tbl-obl", "Obligor financials", TableLayout.COLUMNAR,
                TableEntityKeyLevel.OBLIGOR_ID);
        Measure dscr = measure("DSCR", tbl, "dscr");
        bindCp(master("cm1", "DSCR rule", "DSCR > 1.2", AssessmentType.AUTO), null);
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.of("OBL-1"));
        when(measureBinder.measuresByCode(any())).thenReturn(Map.of("DSCR", dscr));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.empty());

        CpDiagnosticsResponse resp = service.diagnoseCp("CP-1");

        assertThat(resp.obligorId()).isEqualTo("OBL-1");
        TermDiagnostic term = firstTerm(resp);
        assertThat(term.reason()).isEqualTo(DiagnosticReason.NO_OBLIGOR_MAPPING);
        assertThat(term.present()).isFalse();
        assertThat(term.entityKeyLevel()).isEqualTo("OBLIGOR_ID");
        assertThat(term.entityKeyUsed()).isNull();
        assertThat(term.itemCode()).isEqualTo("dscr");
        assertThat(term.tableId()).isEqualTo("tbl-obl");
    }

    @Test
    void measureNotRegisteredWhenBinderCannotBindACatalogMeasure() {
        // The catalog knows DSCR (so the formula compiles), but the binder returns no cov_measure row —
        // the defensive catalog/binder divergence branch.
        bindCp(master("cm1", "DSCR rule", "DSCR > 1.2", AssessmentType.AUTO), null);
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.empty());
        when(measureBinder.measuresByCode(any())).thenReturn(Map.of());

        TermDiagnostic term = firstTerm(service.diagnoseCp("CP-1"));

        assertThat(term.reason()).isEqualTo(DiagnosticReason.MEASURE_NOT_REGISTERED);
        assertThat(term.present()).isFalse();
        assertThat(term.tableId()).isNull();
        assertThat(term.itemCode()).isNull();
        assertThat(term.detail()).contains("no cov_measure row");
    }

    @Test
    void compileErrorMarksCovenantUncompiledWithNoTerms() {
        // NOPE is not in the catalog ⇒ the type checker fails ⇒ compile throws.
        bindCp(master("cm1", "bad", "NOPE > 1", AssessmentType.AUTO), null);
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.empty());

        CovenantDiagnostic cov = service.diagnoseCp("CP-1").covenants().get(0);

        assertThat(cov.compiled()).isFalse();
        assertThat(cov.compileError()).isNotBlank();
        assertThat(cov.wouldResolve()).isFalse();
        assertThat(cov.terms()).isEmpty();
    }

    @Test
    void manualCovenantIsNeverCompiledAndAlwaysResolves() {
        // Blank per-CP override falls back to the master condition (exercises effectiveCondition.isBlank).
        bindCp(master("cm1", "Board resolution", "Confirm board resolution submitted",
                AssessmentType.MANUAL), "  ");

        CovenantDiagnostic cov = service.diagnoseCp("CP-1").covenants().get(0);

        assertThat(cov.assessmentType()).isEqualTo("MANUAL");
        assertThat(cov.compiled()).isTrue();
        assertThat(cov.compileError()).isNull();
        assertThat(cov.wouldResolve()).isTrue();
        assertThat(cov.terms()).isEmpty();
        assertThat(cov.conditionExpression()).isEqualTo("Confirm board resolution submitted");
    }

    @Test
    void perCpOverrideConditionWinsOverMaster() {
        DataSourceTable tbl = cpTable("tbl-fin", "Financials");
        Measure dscr = measure("DSCR", tbl, "dscr");
        bindCp(master("cm1", "DSCR rule", "DSCR > 1.2", AssessmentType.AUTO), "DSCR > 2");
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.empty());
        when(measureBinder.measuresByCode(any())).thenReturn(Map.of("DSCR", dscr));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stagingRepo.countByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1")).thenReturn(1L);
        when(stagingRepo.findByDataSourceTableIdAndEntityKeyAndItemCode("tbl-fin", "CP-1", "dscr"))
                .thenReturn(List.of(num(tbl, "CP-1", LocalDate.now(), "dscr", "1.85")));

        CovenantDiagnostic cov = service.diagnoseCp("CP-1").covenants().get(0);

        assertThat(cov.conditionExpression()).isEqualTo("DSCR > 2");
        assertThat(cov.terms().get(0).reason()).isEqualTo(DiagnosticReason.OK);
    }

    // -- diagnoseAssessment ---------------------------------------------

    @Test
    void diagnoseAssessmentEchoesStoredOutcomeAndReDiagnoses() {
        DataSourceTable tbl = cpTable("tbl-fin", "Financials");
        Measure dscr = measure("DSCR", tbl, "dscr");
        AppliedCovenant ac = applied("ac1", "CP-1",
                master("cm1", "DSCR rule", "DSCR > 1.2", AssessmentType.AUTO), null);
        Assessment asmt = new Assessment();
        ReflectionTestUtils.setField(asmt, "id", "asmt-1");
        asmt.setAppliedCovenant(ac);
        asmt.setResult(AssessmentResult.ERROR);
        asmt.setStatus(AssessmentStatus.COMPLETED);
        asmt.setErrorMessage("Missing values for measures: [DSCR]");
        Instant executedAt = Instant.parse("2026-01-01T00:00:00Z");
        asmt.setExecutedAt(executedAt);
        when(assessmentRepo.findById("asmt-1")).thenReturn(Optional.of(asmt));
        when(measureBinder.measuresByCode(any())).thenReturn(Map.of("DSCR", dscr));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stagingRepo.countByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1")).thenReturn(1L);
        when(stagingRepo.findByDataSourceTableIdAndEntityKeyAndItemCode("tbl-fin", "CP-1", "dscr"))
                .thenReturn(List.of(num(tbl, "CP-1", LocalDate.now(), "dscr", "1.85")));

        AssessmentDiagnosticsResponse resp = service.diagnoseAssessment("asmt-1");

        assertThat(resp.assessmentId()).isEqualTo("asmt-1");
        assertThat(resp.appliedCovenantId()).isEqualTo("ac1");
        assertThat(resp.cpId()).isEqualTo("CP-1");
        assertThat(resp.result()).isEqualTo("ERROR");
        assertThat(resp.status()).isEqualTo("COMPLETED");
        assertThat(resp.errorMessage()).contains("Missing values");
        assertThat(resp.executedAt()).isEqualTo(executedAt);
        assertThat(resp.diagnostic().terms().get(0).reason()).isEqualTo(DiagnosticReason.OK);
    }

    @Test
    void diagnoseAssessmentToleratesNullResultStatusAndTimestamp() {
        AppliedCovenant ac = applied("ac1", "CP-1",
                master("cm1", "Board", "Confirm", AssessmentType.MANUAL), null);
        Assessment asmt = new Assessment();
        ReflectionTestUtils.setField(asmt, "id", "asmt-2");
        asmt.setAppliedCovenant(ac);
        asmt.setResult(null);
        asmt.setStatus(null);
        when(assessmentRepo.findById("asmt-2")).thenReturn(Optional.of(asmt));

        AssessmentDiagnosticsResponse resp = service.diagnoseAssessment("asmt-2");

        assertThat(resp.result()).isNull();
        assertThat(resp.status()).isNull();
        assertThat(resp.errorMessage()).isNull();
        assertThat(resp.executedAt()).isNull();
        assertThat(resp.diagnostic().assessmentType()).isEqualTo("MANUAL");
    }

    @Test
    void diagnoseAssessmentMissingThrowsNotFound() {
        when(assessmentRepo.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.diagnoseAssessment("nope"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // -- inspectStaging -------------------------------------------------

    @Test
    void inspectStagingReturnsRowsNewestFirstAcrossEveryTypedColumn() {
        DataSourceTable tbl = cpTable("tbl-fin", "Financials");
        when(tableRepository.findById("tbl-fin")).thenReturn(Optional.of(tbl));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.of("CP-1"));
        StagingValue number = num(tbl, "CP-1", LocalDate.parse("2025-01-01"), "dscr", "1.85");
        StagingValue text = str(tbl, "CP-1", LocalDate.parse("2025-03-01"), "auditor", "KPMG");
        StagingValue date = date(tbl, "CP-1", LocalDate.parse("2025-02-01"), "lastAudit",
                LocalDate.parse("2024-12-31"));
        StagingValue flag = bool(tbl, "CP-1", LocalDate.parse("2025-04-01"), "flag", true);
        when(stagingRepo.findByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1"))
                .thenReturn(List.of(number, text, date, flag));

        StagingInspectResponse resp = service.inspectStaging("CP-1", "tbl-fin");

        assertThat(resp.tableName()).isEqualTo("Financials");
        assertThat(resp.entityKeyLevel()).isEqualTo("CP_ID");
        assertThat(resp.entityKeyUsed()).isEqualTo("CP-1");
        assertThat(resp.rowCount()).isEqualTo(4);
        assertThat(resp.rows()).extracting(StagingRowView::asOfDate)
                .containsExactly("2025-04-01", "2025-03-01", "2025-02-01", "2025-01-01");
        assertThat(rowFor(resp, "dscr").value()).isEqualTo(new BigDecimal("1.85"));
        assertThat(rowFor(resp, "dscr").dataType()).isEqualTo("NUMBER");
        assertThat(rowFor(resp, "auditor").value()).isEqualTo("KPMG");
        assertThat(rowFor(resp, "lastAudit").value()).isEqualTo("2024-12-31");
        assertThat(rowFor(resp, "flag").value()).isEqualTo(true);
    }

    @Test
    void inspectStagingRendersNullForAnUnpopulatedDateColumn() {
        DataSourceTable tbl = cpTable("tbl-fin", "Financials");
        when(tableRepository.findById("tbl-fin")).thenReturn(Optional.of(tbl));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stagingRepo.findByDataSourceTableIdAndEntityKey("tbl-fin", "CP-1"))
                .thenReturn(List.of(date(tbl, "CP-1", LocalDate.parse("2025-02-01"), "lastAudit", null)));

        StagingInspectResponse resp = service.inspectStaging("CP-1", "tbl-fin");

        assertThat(resp.rows().get(0).value()).isNull();
    }

    @Test
    void inspectStagingMissingTableThrowsNotFound() {
        when(tableRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.inspectStaging("CP-1", "nope"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void inspectStagingReturnsEmptyWhenObligorKeyedTableHasNoMap() {
        DataSourceTable tbl = table("tbl-obl", "Obligor financials", TableLayout.COLUMNAR,
                TableEntityKeyLevel.OBLIGOR_ID);
        when(tableRepository.findById("tbl-obl")).thenReturn(Optional.of(tbl));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.empty());

        StagingInspectResponse resp = service.inspectStaging("CP-1", "tbl-obl");

        assertThat(resp.entityKeyLevel()).isEqualTo("OBLIGOR_ID");
        assertThat(resp.entityKeyUsed()).isNull();
        assertThat(resp.rowCount()).isZero();
        assertThat(resp.rows()).isEmpty();
    }

    // -- ingestionStates ------------------------------------------------

    @Test
    void ingestionStatesListsEachTableSkippingManualAndUncompilableCovenants() {
        DataSourceTable tblA = cpTable("tblA", "A");
        DataSourceTable tblB = cpTable("tblB", "B");
        Measure mDscr = measure("DSCR", tblA, "dscr");
        Measure mRev = measure("REV", tblB, "rev");
        Measure mIcr = new Measure(); // no backing table ⇒ contributes no ingestion-state row
        mIcr.setCode("ICR");
        AppliedCovenant auto = applied("ac1", "CP-1",
                master("cmA", "auto", "DSCR > 1.2 AND REV > 0 AND ICR > 0", AssessmentType.AUTO), null);
        AppliedCovenant manual = applied("ac2", "CP-1",
                master("cmM", "manual", "Confirm board", AssessmentType.MANUAL), null);
        AppliedCovenant bad = applied("ac3", "CP-1",
                master("cmB", "bad", "NOPE > 1", AssessmentType.AUTO), null);
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(auto, manual, bad));
        when(measureBinder.measuresByCode(any()))
                .thenReturn(Map.of("DSCR", mDscr, "REV", mRev, "ICR", mIcr));
        when(entityKeyResolver.entityKeyFor(tblA, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(entityKeyResolver.entityKeyFor(tblB, "CP-1")).thenReturn(Optional.of("CP-1"));
        IngestionState state = new IngestionState();
        state.setStagedAsOf(LocalDate.parse("2025-01-01"));
        state.setStagedIngestedAt(Instant.parse("2025-01-01T00:00:00Z"));
        state.setLastStatus(IngestStatus.OK);
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tblA", "CP-1"))
                .thenReturn(Optional.of(state));
        when(stateRepository.findByDataSourceTableIdAndEntityKey("tblB", "CP-1"))
                .thenReturn(Optional.empty());
        when(stagingRepo.countByDataSourceTableIdAndEntityKey("tblA", "CP-1")).thenReturn(5L);
        when(stagingRepo.countByDataSourceTableIdAndEntityKey("tblB", "CP-1")).thenReturn(0L);
        when(ingestionOrchestration.decide(eq(state), any(), any()))
                .thenReturn(new Decision(false, "fresh"));
        when(ingestionOrchestration.decide(isNull(), any(), any()))
                .thenReturn(new Decision(true, "missing"));

        List<IngestionStateView> views = service.ingestionStates("CP-1");

        assertThat(views).hasSize(2);
        IngestionStateView a = viewFor(views, "tblA");
        assertThat(a.present()).isTrue();
        assertThat(a.entityKeyUsed()).isEqualTo("CP-1");
        assertThat(a.stagedAsOf()).isEqualTo("2025-01-01");
        assertThat(a.lastStatus()).isEqualTo("OK");
        assertThat(a.stagingRowCount()).isEqualTo(5);
        assertThat(a.wouldIngest()).isFalse();
        assertThat(a.decideReason()).isEqualTo("fresh");
        IngestionStateView b = viewFor(views, "tblB");
        assertThat(b.present()).isFalse();
        assertThat(b.stagedAsOf()).isNull();
        assertThat(b.lastStatus()).isNull();
        assertThat(b.stagingRowCount()).isZero();
        assertThat(b.wouldIngest()).isTrue();
        assertThat(b.decideReason()).isEqualTo("missing");
    }

    @Test
    void ingestionStatesFlagsNoObligorMappingTable() {
        DataSourceTable tbl = table("tbl-obl", "Obligor financials", TableLayout.COLUMNAR,
                TableEntityKeyLevel.OBLIGOR_ID);
        Measure dscr = measure("DSCR", tbl, "dscr");
        AppliedCovenant ac = applied("ac1", "CP-1",
                master("cm1", "DSCR rule", "DSCR > 1.2", AssessmentType.AUTO), null);
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(ac));
        when(measureBinder.measuresByCode(any())).thenReturn(Map.of("DSCR", dscr));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.empty());

        List<IngestionStateView> views = service.ingestionStates("CP-1");

        assertThat(views).hasSize(1);
        IngestionStateView v = views.get(0);
        assertThat(v.present()).isFalse();
        assertThat(v.entityKeyUsed()).isNull();
        assertThat(v.entityKeyLevel()).isEqualTo("OBLIGOR_ID");
        assertThat(v.decideReason()).isEqualTo("no-obligor-mapping");
        assertThat(v.wouldIngest()).isFalse();
    }

    // -- fixtures -------------------------------------------------------

    /** Bind a single applied covenant (derived from {@code master}, with an optional override) to CP-1. */
    private AppliedCovenant bindCp(CovenantMaster master, String override) {
        AppliedCovenant ac = applied("ac1", "CP-1", master, override);
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(ac));
        return ac;
    }

    private static AppliedCovenant applied(String id, String cpId, CovenantMaster master, String override) {
        AppliedCovenant ac = new AppliedCovenant();
        ReflectionTestUtils.setField(ac, "id", id);
        ac.setCpId(cpId);
        ac.setCovenantMaster(master);
        ac.setConditionExpression(override);
        return ac;
    }

    private static CovenantMaster master(String id, String name, String condition, AssessmentType type) {
        CovenantMaster master = new CovenantMaster();
        ReflectionTestUtils.setField(master, "id", id);
        master.setName(name);
        master.setConditionExpression(condition);
        master.setAssessmentType(type);
        return master;
    }

    private static DataSourceTable cpTable(String id, String name) {
        return table(id, name, TableLayout.COLUMNAR, TableEntityKeyLevel.CP_ID);
    }

    private static DataSourceTable table(String id, String name, TableLayout layout,
                                         TableEntityKeyLevel level) {
        DataSourceTable t = new DataSourceTable();
        ReflectionTestUtils.setField(t, "id", id);
        t.setName(name);
        t.setLayout(layout);
        t.setEntityKeyLevel(level);
        return t;
    }

    private static Measure measure(String code, DataSourceTable table, String sourceColumn) {
        Measure m = new Measure();
        ReflectionTestUtils.setField(m, "id", code + "-id");
        m.setCode(code);
        m.setDataSourceTable(table);
        m.setSourceColumn(sourceColumn);
        m.setDataType(DataColumnType.NUMBER);
        return m;
    }

    private static StagingValue num(DataSourceTable table, String entityKey, LocalDate asOf,
                                    String itemCode, String value) {
        StagingValue s = staging(table, entityKey, asOf, itemCode, DataColumnType.NUMBER);
        s.setNumValue(new BigDecimal(value));
        return s;
    }

    private static StagingValue str(DataSourceTable table, String entityKey, LocalDate asOf,
                                    String itemCode, String value) {
        StagingValue s = staging(table, entityKey, asOf, itemCode, DataColumnType.STRING);
        s.setStrValue(value);
        return s;
    }

    private static StagingValue date(DataSourceTable table, String entityKey, LocalDate asOf,
                                     String itemCode, LocalDate value) {
        StagingValue s = staging(table, entityKey, asOf, itemCode, DataColumnType.DATE);
        s.setDateValue(value);
        return s;
    }

    private static StagingValue bool(DataSourceTable table, String entityKey, LocalDate asOf,
                                     String itemCode, boolean value) {
        StagingValue s = staging(table, entityKey, asOf, itemCode, DataColumnType.BOOLEAN);
        s.setBoolValue(value);
        return s;
    }

    private static StagingValue staging(DataSourceTable table, String entityKey, LocalDate asOf,
                                        String itemCode, DataColumnType type) {
        StagingValue s = new StagingValue();
        s.setDataSourceTable(table);
        s.setEntityKey(entityKey);
        s.setAsOfDate(asOf);
        s.setItemCode(itemCode);
        s.setDataType(type);
        s.setIngestedAt(Instant.parse("2025-06-01T00:00:00Z"));
        s.setIngestBatchId("batch-1");
        return s;
    }

    private static TermDiagnostic firstTerm(CpDiagnosticsResponse resp) {
        return resp.covenants().get(0).terms().get(0);
    }

    private static StagingRowView rowFor(StagingInspectResponse resp, String itemCode) {
        return resp.rows().stream().filter(r -> itemCode.equals(r.itemCode())).findFirst().orElseThrow();
    }

    private static IngestionStateView viewFor(List<IngestionStateView> views, String tableId) {
        return views.stream().filter(v -> tableId.equals(v.tableId())).findFirst().orElseThrow();
    }

    private static MeasureCatalog catalog() {
        Map<String, MeasureMeta> byCode = new LinkedHashMap<>();
        for (String code : List.of("DSCR", "PAT", "ICR", "REV")) {
            byCode.put(code, MeasureMeta.of(code, FormulaType.NUMBER));
        }
        return new MeasureCatalog() {
            @Override
            public Optional<MeasureMeta> lookup(String code) {
                return Optional.ofNullable(byCode.get(code));
            }

            @Override
            public Collection<String> names() {
                return byCode.keySet();
            }
        };
    }
}
