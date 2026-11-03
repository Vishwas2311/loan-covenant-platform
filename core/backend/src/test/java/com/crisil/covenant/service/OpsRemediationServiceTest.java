package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ops.RemediationResult;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaType;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.formula.MeasureMeta;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.IngestionStateRepository;
import com.crisil.covenant.repository.MeasureRepository;
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
 * Unit tests for the safe-remediation service. A <em>real</em> {@link FormulaEngine} and
 * {@link FormulaMeasureBinder} (over a mocked {@link MeasureRepository}) drive table discovery through
 * the true compile/bind path, while the freshness gate, entity-key resolver, assessment runner and
 * audit trail are mocked so each branch — re-ingest, clear-state, reassess, obligor-skip, warnings and
 * audited failure — is asserted in isolation.
 */
@ExtendWith(MockitoExtension.class)
class OpsRemediationServiceTest {

    @Mock private AppliedCovenantRepository appliedRepo;
    @Mock private MeasureCatalogService measureCatalog;
    @Mock private MeasureRepository measureRepository;
    @Mock private SupportEntityKeyResolver entityKeyResolver;
    @Mock private IngestionOrchestrationService ingestionOrchestration;
    @Mock private IngestionStateRepository stateRepository;
    @Mock private AssessmentService assessmentService;
    @Mock private OpsAuditService auditService;

    private final FormulaEngine formulaEngine = new FormulaEngine();
    private OpsRemediationService service;

    @BeforeEach
    void setUp() {
        FormulaMeasureBinder measureBinder = new FormulaMeasureBinder(measureRepository);
        service = new OpsRemediationService(appliedRepo, formulaEngine, measureCatalog, measureBinder,
                entityKeyResolver, ingestionOrchestration, stateRepository, assessmentService,
                auditService);
        // reingest/clear snapshot the active catalog to compile; reassess does not.
        lenient().when(measureCatalog.activeCatalog()).thenReturn(catalog());
    }

    // -- reingestCp -----------------------------------------------------

    @Test
    void reingestCpForceRefreshesAndIngestsEachDistinctTable() {
        DataSourceTable tblA = cpTable("tblA", "A");
        DataSourceTable tblB = cpTable("tblB", "B");
        bindCp(master("cm1", "two-table", "DSCR > 1.2 AND REV > 0", AssessmentType.AUTO), null);
        when(measureRepository.findByCodeIn(any()))
                .thenReturn(List.of(measure("DSCR", tblA), measure("REV", tblB)));
        when(entityKeyResolver.entityKeyFor(tblA, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(entityKeyResolver.entityKeyFor(tblB, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(ingestionOrchestration.ingestIfStale(any(), eq("CP-1"), any(), any())).thenReturn(true);

        RemediationResult result = service.reingestCp("CP-1", null);

        assertThat(result.action()).isEqualTo("reingest");
        assertThat(result.cpId()).isEqualTo("CP-1");
        assertThat(result.appliedCovenantId()).isNull();
        assertThat(result.assessmentResult()).isNull();
        assertThat(result.ingested()).isTrue();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.tablesTouched()).containsExactlyInAnyOrder("tblA", "tblB");
        verify(ingestionOrchestration).forceRefresh(tblA, "CP-1");
        verify(ingestionOrchestration).forceRefresh(tblB, "CP-1");
        verify(auditService).recordSuccess(eq("REINGEST_CP"), eq("CP"), eq("CP-1"), any(), any());
    }

    @Test
    void reingestCpDedupsTableSharedAcrossCovenants() {
        DataSourceTable tblA = cpTable("tblA", "A");
        AppliedCovenant one = applied("ac1", "CP-1",
                master("cm1", "r1", "DSCR > 1", AssessmentType.AUTO), null);
        AppliedCovenant two = applied("ac2", "CP-1",
                master("cm2", "r2", "DSCR > 2", AssessmentType.AUTO), null);
        when(appliedRepo.findByCpIdOrderByIdAsc("CP-1")).thenReturn(List.of(one, two));
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", tblA)));
        when(entityKeyResolver.entityKeyFor(tblA, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(ingestionOrchestration.ingestIfStale(any(), eq("CP-1"), any(), any())).thenReturn(true);

        RemediationResult result = service.reingestCp("CP-1", null);

        assertThat(result.tablesTouched()).containsExactly("tblA");
        verify(ingestionOrchestration).forceRefresh(tblA, "CP-1");
        verify(ingestionOrchestration).ingestIfStale(eq(tblA), eq("CP-1"), any(), any());
    }

    @Test
    void reingestCpScopedToSingleTableIgnoresOthers() {
        DataSourceTable tblA = cpTable("tblA", "A");
        DataSourceTable tblB = cpTable("tblB", "B");
        bindCp(master("cm1", "two-table", "DSCR > 1 AND REV > 0", AssessmentType.AUTO), null);
        when(measureRepository.findByCodeIn(any()))
                .thenReturn(List.of(measure("DSCR", tblA), measure("REV", tblB)));
        when(entityKeyResolver.entityKeyFor(tblB, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(ingestionOrchestration.ingestIfStale(any(), eq("CP-1"), any(), any())).thenReturn(true);

        RemediationResult result = service.reingestCp("CP-1", "tblB");

        assertThat(result.tablesTouched()).containsExactly("tblB");
        assertThat(result.warnings()).isEmpty();
        verify(ingestionOrchestration).forceRefresh(tblB, "CP-1");
        verify(ingestionOrchestration, never()).forceRefresh(eq(tblA), any());
    }

    @Test
    void reingestCpWarnsWhenScopedTableIsNotUsedByAnyCovenant() {
        DataSourceTable tblA = cpTable("tblA", "A");
        bindCp(master("cm1", "r", "DSCR > 1", AssessmentType.AUTO), null);
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", tblA)));

        RemediationResult result = service.reingestCp("CP-1", "tblZ");

        assertThat(result.tablesTouched()).isEmpty();
        assertThat(result.ingested()).isFalse();
        assertThat(result.warnings()).anyMatch(w -> w.contains("not used"));
        verify(ingestionOrchestration, never()).forceRefresh(any(), any());
        verify(auditService).recordSuccess(eq("REINGEST_CP"), eq("CP"), eq("CP-1"), any(), any());
    }

    @Test
    void reingestCpSkipsObligorTableWithNoMappingAndWarns() {
        DataSourceTable tbl = table("tbl-obl", "Obligor financials", TableLayout.COLUMNAR,
                TableEntityKeyLevel.OBLIGOR_ID);
        bindCp(master("cm1", "r", "DSCR > 1", AssessmentType.AUTO), null);
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", tbl)));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.empty());

        RemediationResult result = service.reingestCp("CP-1", null);

        assertThat(result.tablesTouched()).isEmpty();
        assertThat(result.ingested()).isFalse();
        assertThat(result.warnings()).anyMatch(w -> w.contains("obligor-keyed"));
        verify(ingestionOrchestration, never()).forceRefresh(any(), any());
    }

    @Test
    void reingestCpWarnsForUncompilableCovenant() {
        bindCp(master("cm1", "bad", "NOPE > 1", AssessmentType.AUTO), null);

        RemediationResult result = service.reingestCp("CP-1", null);

        assertThat(result.tablesTouched()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("did not compile"));
        verify(ingestionOrchestration, never()).forceRefresh(any(), any());
    }

    @Test
    void reingestCpWarnsForUnregisteredMeasure() {
        bindCp(master("cm1", "r", "DSCR > 1", AssessmentType.AUTO), null);
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of());

        RemediationResult result = service.reingestCp("CP-1", null);

        assertThat(result.tablesTouched()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("unregistered measure"));
        verify(ingestionOrchestration, never()).forceRefresh(any(), any());
    }

    @Test
    void reingestCpAuditsFailureAndRethrows() {
        DataSourceTable tblA = cpTable("tblA", "A");
        bindCp(master("cm1", "r", "DSCR > 1", AssessmentType.AUTO), null);
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", tblA)));
        when(entityKeyResolver.entityKeyFor(tblA, "CP-1")).thenReturn(Optional.of("CP-1"));
        doThrow(new IllegalStateException("boom")).when(ingestionOrchestration)
                .forceRefresh(tblA, "CP-1");

        assertThatThrownBy(() -> service.reingestCp("CP-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        verify(auditService).recordFailure(eq("REINGEST_CP"), eq("CP"), eq("CP-1"), any(), any());
        verify(auditService, never()).recordSuccess(any(), any(), any(), any(), any());
    }

    // -- clearIngestionState --------------------------------------------

    @Test
    void clearIngestionStateDeletesControlRow() {
        DataSourceTable tblA = cpTable("tblA", "A");
        bindCp(master("cm1", "r", "DSCR > 1", AssessmentType.AUTO), null);
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", tblA)));
        when(entityKeyResolver.entityKeyFor(tblA, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stateRepository.deleteByDataSourceTableIdAndEntityKey("tblA", "CP-1")).thenReturn(1L);

        RemediationResult result = service.clearIngestionState("CP-1", null);

        assertThat(result.action()).isEqualTo("clear-ingestion-state");
        assertThat(result.tablesTouched()).containsExactly("tblA");
        assertThat(result.ingested()).isFalse();
        assertThat(result.warnings()).isEmpty();
        verify(stateRepository).deleteByDataSourceTableIdAndEntityKey("tblA", "CP-1");
        verify(auditService).recordSuccess(eq("CLEAR_INGESTION_STATE"), eq("CP"), eq("CP-1"),
                any(), any());
    }

    @Test
    void clearIngestionStateWarnsWhenNoRowToClear() {
        DataSourceTable tblA = cpTable("tblA", "A");
        bindCp(master("cm1", "r", "DSCR > 1", AssessmentType.AUTO), null);
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", tblA)));
        when(entityKeyResolver.entityKeyFor(tblA, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stateRepository.deleteByDataSourceTableIdAndEntityKey("tblA", "CP-1")).thenReturn(0L);

        RemediationResult result = service.clearIngestionState("CP-1", null);

        assertThat(result.tablesTouched()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("nothing to clear"));
    }

    @Test
    void clearIngestionStateSkipsObligorTableWithNoMapping() {
        DataSourceTable tbl = table("tbl-obl", "Obligor financials", TableLayout.COLUMNAR,
                TableEntityKeyLevel.OBLIGOR_ID);
        bindCp(master("cm1", "r", "DSCR > 1", AssessmentType.AUTO), null);
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", tbl)));
        when(entityKeyResolver.entityKeyFor(tbl, "CP-1")).thenReturn(Optional.empty());

        RemediationResult result = service.clearIngestionState("CP-1", null);

        assertThat(result.tablesTouched()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("obligor-keyed"));
        verify(stateRepository, never()).deleteByDataSourceTableIdAndEntityKey(any(), any());
    }

    @Test
    void clearIngestionStateAuditsFailureAndRethrows() {
        DataSourceTable tblA = cpTable("tblA", "A");
        bindCp(master("cm1", "r", "DSCR > 1", AssessmentType.AUTO), null);
        when(measureRepository.findByCodeIn(any())).thenReturn(List.of(measure("DSCR", tblA)));
        when(entityKeyResolver.entityKeyFor(tblA, "CP-1")).thenReturn(Optional.of("CP-1"));
        when(stateRepository.deleteByDataSourceTableIdAndEntityKey("tblA", "CP-1"))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.clearIngestionState("CP-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");

        verify(auditService).recordFailure(eq("CLEAR_INGESTION_STATE"), eq("CP"), eq("CP-1"),
                any(), any());
        verify(auditService, never()).recordSuccess(any(), any(), any(), any(), any());
    }

    // -- reassess -------------------------------------------------------

    @Test
    void reassessDelegatesWithManualTriggerAndEchoesResult() {
        Assessment assessment = assessment("CP-9", AssessmentResult.COMPLIANT);
        when(assessmentService.runAssessment(eq("ac1"), eq(AssessmentTrigger.MANUAL), isNull()))
                .thenReturn(assessment);

        RemediationResult result = service.reassess("ac1");

        assertThat(result.action()).isEqualTo("reassess");
        assertThat(result.appliedCovenantId()).isEqualTo("ac1");
        assertThat(result.cpId()).isEqualTo("CP-9");
        assertThat(result.assessmentResult()).isEqualTo("COMPLIANT");
        assertThat(result.tablesTouched()).isEmpty();
        assertThat(result.ingested()).isFalse();
        assertThat(result.warnings()).isEmpty();
        verify(assessmentService).runAssessment("ac1", AssessmentTrigger.MANUAL, null);
        verify(auditService).recordSuccess(eq("REASSESS"), eq("APPLIED_COVENANT"), eq("ac1"),
                any(), any());
    }

    @Test
    void reassessEchoesNullResultWhenAssessmentHasNoOutcomeYet() {
        Assessment assessment = assessment("CP-9", null);
        when(assessmentService.runAssessment(eq("ac1"), eq(AssessmentTrigger.MANUAL), isNull()))
                .thenReturn(assessment);

        RemediationResult result = service.reassess("ac1");

        assertThat(result.assessmentResult()).isNull();
    }

    @Test
    void reassessAuditsFailureAndRethrows() {
        when(assessmentService.runAssessment(eq("bad"), eq(AssessmentTrigger.MANUAL), isNull()))
                .thenThrow(new ResourceNotFoundException("Applied covenant bad not found"));

        assertThatThrownBy(() -> service.reassess("bad"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(auditService).recordFailure(eq("REASSESS"), eq("APPLIED_COVENANT"), eq("bad"),
                any(), any());
        verify(auditService, never()).recordSuccess(any(), any(), any(), any(), any());
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

    private static Assessment assessment(String cpId, AssessmentResult result) {
        AppliedCovenant ac = new AppliedCovenant();
        ac.setCpId(cpId);
        Assessment assessment = new Assessment();
        assessment.setAppliedCovenant(ac);
        assessment.setResult(result);
        return assessment;
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

    private static Measure measure(String code, DataSourceTable table) {
        Measure m = new Measure();
        ReflectionTestUtils.setField(m, "id", code + "-id");
        m.setCode(code);
        m.setDataSourceTable(table);
        m.setSourceColumn(code);
        m.setDataType(DataColumnType.NUMBER);
        return m;
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
