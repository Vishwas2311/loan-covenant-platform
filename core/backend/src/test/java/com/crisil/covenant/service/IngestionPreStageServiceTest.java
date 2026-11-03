package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.config.IngestionProperties;
import com.crisil.covenant.dto.PreStageResult;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.formula.CompiledFormula;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaException;
import com.crisil.covenant.formula.FormulaTerm;
import com.crisil.covenant.formula.FunctionKind;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.formula.PeriodInclusion;
import com.crisil.covenant.formula.SamplingMode;
import com.crisil.covenant.formula.Window;
import com.crisil.covenant.formula.WindowUnit;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.repository.AssessmentRepository.AppliedCovenantLastExecuted;
import com.crisil.covenant.repository.MeasureRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * US-DS-13 (AC4/AC5/AC9): the due-driven pre-stage sweep — due detection within the lookahead horizon,
 * coalescing many covenants into one extract per (table, entity), obligor-grain fan-out, and per-entity
 * failure isolation.
 */
@ExtendWith(MockitoExtension.class)
class IngestionPreStageServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 21);

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private AssessmentRepository assessmentRepo;
    @Mock private FormulaEngine formulaEngine;
    @Mock private MeasureCatalogService measureCatalog;
    @Mock private MeasureRepository measureRepository;
    @Mock private ObligorMappingService obligorMappingService;
    @Mock private IngestionOrchestrationService ingestionOrchestration;
    @Mock private IngestionProperties properties;
    @InjectMocks private IngestionPreStageService service;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getLookaheadDays()).thenReturn(3);
        lenient().when(measureCatalog.activeCatalog()).thenReturn(mock(MeasureCatalog.class));
        // Default: covenants were never assessed → started ones are OVERDUE (due).
        lenient().when(assessmentRepo.findLatestExecutedAtByAppliedCovenantIdIn(anyList()))
                .thenReturn(List.of());
        lenient().when(ingestionOrchestration.ingestIfStale(any(), anyString(), any(), any()))
                .thenReturn(true);
    }

    @Test
    void coalescesManyCpsOfOneObligorIntoOneWidestExtract() {
        DataSourceTable obl = table("tbl-obl", TableEntityKeyLevel.OBLIGOR_ID);
        // Two CPs of the same obligor, each with a windowed term of a different depth on the SAME table.
        AppliedCovenant cov1 = overdue("1", "CP-1", "c1");
        AppliedCovenant cov2 = overdue("2", "CP-2", "c2");
        stubCompile("c1", term("PAT", obl, new Window(2, WindowUnit.Y), SamplingMode.FLAT));
        stubCompile("c2", term("REV", obl, new Window(6, WindowUnit.M), SamplingMode.FLAT));
        when(measureRepository.findByCodeIn(any()))
                .thenReturn(List.of(measure("PAT", obl), measure("REV", obl)));
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.of("OBL-1"));
        when(obligorMappingService.obligorFor("CP-2")).thenReturn(Optional.of("OBL-1"));
        onePage(cov1, cov2);

        PreStageResult result = service.preStage(TODAY);

        // 500-covenants-become-one: a single extract at obligor grain, widest (2-year) look-back.
        assertThat(result.candidateTargets()).isEqualTo(1);
        assertThat(result.ingested()).isEqualTo(1);
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        verify(ingestionOrchestration, times(1))
                .ingestIfStale(eq(obl), eq("OBL-1"), from.capture(), eq(TODAY));
        assertThat(from.getValue()).isEqualTo(TODAY.minusYears(2));
    }

    @Test
    void ingestsOneCpTargetAndReportsIngested() {
        DataSourceTable t = table("tbl-1", TableEntityKeyLevel.CP_ID);
        AppliedCovenant cov = overdue("1", "CP-1", "c1");
        stubCompile("c1", term("DSCR", t, null, SamplingMode.FLAT)); // point-in-time
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", t)));
        onePage(cov);

        PreStageResult result = service.preStage(TODAY);

        assertThat(result.candidateTargets()).isEqualTo(1);
        assertThat(result.ingested()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        // Point-in-time term needs only the as-of ceiling as its lower bound.
        verify(ingestionOrchestration).ingestIfStale(eq(t), eq("CP-1"), eq(TODAY), eq(TODAY));
    }

    @Test
    void widensLowerBoundByOneExtraPeriodForPeriodicSampling() {
        DataSourceTable t = table("tbl-1", TableEntityKeyLevel.CP_ID);
        AppliedCovenant cov = overdue("1", "CP-1", "c1");
        stubCompile("c1", term("PAT", t, new Window(3, WindowUnit.Y), SamplingMode.PERIODIC));
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("PAT", t)));
        onePage(cov);

        service.preStage(TODAY);

        // 3-year window + one extra year of PERIODIC head-room = 4 years back.
        verify(ingestionOrchestration).ingestIfStale(eq(t), eq("CP-1"), eq(TODAY.minusYears(4)), eq(TODAY));
    }

    @Test
    void countsGateSkipVersusIngestPerTarget() {
        DataSourceTable t = table("tbl-1", TableEntityKeyLevel.CP_ID);
        AppliedCovenant cov1 = overdue("1", "CP-1", "c1");
        AppliedCovenant cov2 = overdue("2", "CP-2", "c1");
        stubCompile("c1", term("DSCR", t, null, SamplingMode.FLAT));
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", t)));
        // CP-1 fresh (gate skips), CP-2 stale (gate ingests).
        when(ingestionOrchestration.ingestIfStale(eq(t), eq("CP-1"), any(), any())).thenReturn(false);
        when(ingestionOrchestration.ingestIfStale(eq(t), eq("CP-2"), any(), any())).thenReturn(true);
        onePage(cov1, cov2);

        PreStageResult result = service.preStage(TODAY);

        assertThat(result.candidateTargets()).isEqualTo(2);
        assertThat(result.ingested()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void isolatesAFailingEntityFromTheRestOfTheSweep() {
        DataSourceTable t = table("tbl-1", TableEntityKeyLevel.CP_ID);
        AppliedCovenant cov1 = overdue("1", "CP-1", "c1");
        AppliedCovenant cov2 = overdue("2", "CP-2", "c1");
        stubCompile("c1", term("DSCR", t, null, SamplingMode.FLAT));
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", t)));
        doThrow(new RuntimeException("live source down\r\ninjected"))
                .when(ingestionOrchestration).ingestIfStale(eq(t), eq("CP-1"), any(), any());
        when(ingestionOrchestration.ingestIfStale(eq(t), eq("CP-2"), any(), any())).thenReturn(true);
        onePage(cov1, cov2);

        PreStageResult result = service.preStage(TODAY);

        assertThat(result.candidateTargets()).isEqualTo(2);
        assertThat(result.ingested()).isEqualTo(1); // CP-2 still processed after CP-1 failed
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void skipsCovenantsNotDueWithinHorizon() {
        DataSourceTable t = table("tbl-1", TableEntityKeyLevel.CP_ID);
        AppliedCovenant ended = overdue("1", "CP-1", "c1");
        ended.setEndDate(TODAY.minusDays(1)); // ended yesterday → ENDED, never due
        onePage(ended);

        PreStageResult result = service.preStage(TODAY);

        assertThat(result.candidateTargets()).isZero();
        verify(ingestionOrchestration, never()).ingestIfStale(any(), anyString(), any(), any());
    }

    @Test
    void preStagesADueSoonCovenantWithinTheLookahead() {
        DataSourceTable t = table("tbl-1", TableEntityKeyLevel.CP_ID);
        // Assessed today ⇒ next daily occurrence is tomorrow, inside the 3-day lookahead (DUE_SOON, not OVERDUE).
        AppliedCovenant cov = overdue("1", "CP-1", "c1");
        when(assessmentRepo.findLatestExecutedAtByAppliedCovenantIdIn(anyList()))
                .thenReturn(List.of(lastExecuted("1", TODAY.atTime(6, 30)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant())));
        stubCompile("c1", term("DSCR", t, null, SamplingMode.FLAT));
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", t)));
        onePage(cov);

        PreStageResult result = service.preStage(TODAY);

        assertThat(result.candidateTargets()).isEqualTo(1);
        verify(ingestionOrchestration).ingestIfStale(eq(t), eq("CP-1"), any(), eq(TODAY));
    }

    @Test
    void skipsAnUpToDateCovenantBeyondTheLookahead() {
        // Assessed today on a MONTHLY cadence ⇒ next due ~a month out, beyond the 3-day lookahead.
        AppliedCovenant cov = overdue("1", "CP-1", "c1");
        cov.setFrequency(Frequency.MONTHLY);
        cov.setCronExpression(Frequency.MONTHLY.defaultCron());
        when(assessmentRepo.findLatestExecutedAtByAppliedCovenantIdIn(anyList()))
                .thenReturn(List.of(lastExecuted("1", TODAY.atTime(6, 30)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant())));
        onePage(cov);

        PreStageResult result = service.preStage(TODAY);

        assertThat(result.candidateTargets()).isZero();
        verify(ingestionOrchestration, never()).ingestIfStale(any(), anyString(), any(), any());
    }

    @Test
    void obligorKeyedCovenantWithNoMappingContributesNoTarget() {
        DataSourceTable obl = table("tbl-obl", TableEntityKeyLevel.OBLIGOR_ID);
        AppliedCovenant cov = overdue("1", "CP-1", "c1");
        stubCompile("c1", term("DSCR", obl, null, SamplingMode.FLAT));
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", obl)));
        when(obligorMappingService.obligorFor("CP-1")).thenReturn(Optional.empty());
        onePage(cov);

        PreStageResult result = service.preStage(TODAY);

        assertThat(result.candidateTargets()).isZero();
        verify(ingestionOrchestration, never()).ingestIfStale(any(), anyString(), any(), any());
    }

    @Test
    void skipsCovenantWhoseConditionDoesNotCompile() {
        AppliedCovenant cov = overdue("1", "CP-1", "bad");
        when(formulaEngine.compile(eq("bad"), any())).thenThrow(new FormulaException("syntax boom"));
        onePage(cov);

        PreStageResult result = service.preStage(TODAY);

        assertThat(result.candidateTargets()).isZero();
        verify(ingestionOrchestration, never()).ingestIfStale(any(), anyString(), any(), any());
    }

    @Test
    void skipsCovenantReferencingAnUnregisteredMeasure() {
        DataSourceTable t = table("tbl-1", TableEntityKeyLevel.CP_ID);
        AppliedCovenant cov = overdue("1", "CP-1", "c1");
        stubCompile("c1", term("GHOST", t, null, SamplingMode.FLAT));
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of()); // no measure resolves
        onePage(cov);

        PreStageResult result = service.preStage(TODAY);

        assertThat(result.candidateTargets()).isZero();
        verify(ingestionOrchestration, never()).ingestIfStale(any(), anyString(), any(), any());
    }

    @Test
    void ignoresTermsWithoutADataSourceTable() {
        AppliedCovenant cov = overdue("1", "CP-1", "c1");
        stubCompile("c1", term("DERIVED", null, null, SamplingMode.FLAT)); // no backing table
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DERIVED", null)));
        onePage(cov);

        PreStageResult result = service.preStage(TODAY);

        assertThat(result.candidateTargets()).isZero();
        verify(ingestionOrchestration, never()).ingestIfStale(any(), anyString(), any(), any());
    }

    @Test
    void streamsEveryPageOfTheActiveBook() {
        DataSourceTable t = table("tbl-1", TableEntityKeyLevel.CP_ID);
        AppliedCovenant cov1 = overdue("1", "CP-1", "c1");
        AppliedCovenant cov2 = overdue("2", "CP-2", "c1");
        stubCompile("c1", term("DSCR", t, null, SamplingMode.FLAT));
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", t)));
        // Page 0 has a next page; page 1 is the last. total=2, size=500 would not span pages, so fake
        // the span with an inflated total so hasNext() is true after page 0.
        Pageable p0 = PageRequest.of(0, 500);
        Pageable p1 = PageRequest.of(1, 500);
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any()))
                .thenReturn(new PageImpl<>(List.of(cov1), p0, 501))
                .thenReturn(new PageImpl<>(List.of(cov2), p1, 501));

        PreStageResult result = service.preStage(TODAY);

        assertThat(result.candidateTargets()).isEqualTo(2); // CP-1 (page0) + CP-2 (page1)
        verify(appliedRepo, times(2)).findByStatus(eq(AppliedCovenantStatus.ACTIVE), any());
    }

    @Test
    void defaultsToTodayWhenRunDateIsNull() {
        onePage(); // empty page

        PreStageResult result = service.preStage(null);

        assertThat(result.candidateTargets()).isZero();
        verify(appliedRepo).findByStatus(eq(AppliedCovenantStatus.ACTIVE), any());
    }

    // --- helpers ---

    private void onePage(AppliedCovenant... covenants) {
        when(appliedRepo.findByStatus(eq(AppliedCovenantStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(covenants)));
    }

    private void stubCompile(String condition, FormulaTerm term) {
        CompiledFormula compiled = mock(CompiledFormula.class);
        lenient().when(compiled.terms()).thenReturn(List.of(term));
        List<String> refs = new ArrayList<>();
        refs.add(term.measure());
        lenient().when(compiled.referencedMeasures()).thenReturn(refs);
        when(formulaEngine.compile(eq(condition), any())).thenReturn(compiled);
    }

    private static FormulaTerm term(String measure, DataSourceTable ignoredTable, Window window,
                                    SamplingMode sampling) {
        return new FormulaTerm(measure, window == null ? null : FunctionKind.AVG, window,
                sampling, PeriodInclusion.DEFAULT, measure);
    }

    private static AppliedCovenant overdue(String id, String cpId, String condition) {
        CovenantMaster master = new CovenantMaster();
        setId(master, "m-" + id);
        master.setConditionExpression(condition);

        AppliedCovenant a = new AppliedCovenant();
        setId(a, id);
        a.setCpId(cpId);
        a.setCovenantMaster(master);
        a.setConditionExpression(condition);
        a.setFrequency(Frequency.DAILY);
        a.setCronExpression(Frequency.DAILY.defaultCron());
        a.setStartDate(TODAY.minusDays(30)); // started, never assessed → OVERDUE by default
        return a;
    }

    private static Measure measure(String code, DataSourceTable table) {
        Measure m = new Measure();
        m.setCode(code);
        m.setDataSourceTable(table);
        m.setSourceColumn(code.toLowerCase(Locale.ROOT));
        m.setDataType(DataColumnType.NUMBER);
        return m;
    }

    private static DataSourceTable table(String id, TableEntityKeyLevel level) {
        DataSourceTable t = new DataSourceTable();
        setId(t, id);
        t.setLayout(TableLayout.COLUMNAR);
        t.setEntityKeyLevel(level);
        return t;
    }

    private static AppliedCovenantLastExecuted lastExecuted(String id, Instant at) {
        return new AppliedCovenantLastExecuted() {
            @Override
            public String getAppliedCovenantId() {
                return id;
            }

            @Override
            public Instant getLastExecutedAt() {
                return at;
            }
        };
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
