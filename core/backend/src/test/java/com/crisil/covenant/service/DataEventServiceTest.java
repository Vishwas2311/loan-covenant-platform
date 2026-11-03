package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.DataEventRequest;
import com.crisil.covenant.dto.DataEventResponse;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.DataEvent;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.formula.CompiledFormula;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaException;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.DataEventRepository;
import com.crisil.covenant.repository.DataSourceTableRepository;
import com.crisil.covenant.repository.MeasureRepository;
import com.crisil.covenant.repository.ObligorCpMapRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DataEventServiceTest {

    @Mock DataEventRepository eventRepo;
    @Mock DataSourceTableRepository tableRepo;
    @Mock MeasureRepository measureRepo;
    @Mock ObligorCpMapRepository obligorRepo;
    @Mock AppliedCovenantRepository appliedRepo;
    @Mock IngestionOrchestrationService ingestion;
    @Mock AssessmentService assessmentService;
    @Mock FormulaEngine formulaEngine;
    @Mock MeasureCatalogService catalogService;
    DataEventService service;

    static final Instant CHANGED = Instant.parse("2026-04-12T10:03:00Z");

    @BeforeEach
    void setUp() {
        service = new DataEventService(eventRepo, tableRepo, measureRepo, obligorRepo, appliedRepo, ingestion,
                assessmentService, formulaEngine, catalogService);
    }

    static DataEventRequest req(TableEntityKeyLevel level, String entity) {
        return new DataEventRequest("ds-dps", entity, level, LocalDate.of(2026, 3, 31), CHANGED, "doc-1");
    }

    static DataSourceTable table(String id, TableEntityKeyLevel level) {
        DataSourceTable t = new DataSourceTable();
        ReflectionTestUtils.setField(t, "id", id);
        t.setEntityKeyLevel(level);
        return t;
    }

    static Measure measure(String code) {
        Measure m = new Measure();
        m.setCode(code);
        return m;
    }

    static AppliedCovenant applied(String id, AppliedCovenantStatus status, AssessmentType type, String override,
                                   String masterCondition) {
        CovenantMaster master = new CovenantMaster();
        master.setAssessmentType(type);
        master.setConditionExpression(masterCondition);
        AppliedCovenant ac = new AppliedCovenant();
        ReflectionTestUtils.setField(ac, "id", id);
        ac.setCovenantMaster(master);
        ac.setStatus(status);
        ac.setConditionExpression(override);
        return ac;
    }

    static CompiledFormula compiled(String... measures) {
        CompiledFormula f = mock(CompiledFormula.class);
        when(f.referencedMeasures()).thenReturn(List.of(measures));
        return f;
    }

    @Test
    void duplicateEventIsNoOp() {
        when(eventRepo.existsByDataSourceIdAndEntityKeyAndChangedAt("ds-dps", "CP-1", CHANGED)).thenReturn(true);
        assertThat(service.handle(req(TableEntityKeyLevel.CP_ID, "CP-1"))).isEqualTo(new DataEventResponse(0, true));
        verify(eventRepo, never()).save(any());
    }

    @Test
    void unknownSourceOrLevelIs404() {
        when(tableRepo.findByDataSourceIdOrderByNameAsc("ds-dps"))
                .thenReturn(List.of(table("t-obl", TableEntityKeyLevel.OBLIGOR_ID)));
        assertThatThrownBy(() -> service.handle(req(TableEntityKeyLevel.CP_ID, "CP-1")))
                .isInstanceOf(ApiException.class).hasMessageContaining("No CP_ID table");
    }

    @Test
    void cpEventRecordsChangeAndReassessesOnlyAffectedActiveAutoCovenants() {
        DataSourceTable t = table("t1", TableEntityKeyLevel.CP_ID);
        when(tableRepo.findByDataSourceIdOrderByNameAsc("ds-dps")).thenReturn(List.of(t));
        when(measureRepo.findByDataSourceTableId("t1")).thenReturn(List.of(measure("dps_ebitda")));
        MeasureCatalog catalog = mock(MeasureCatalog.class);
        when(catalogService.activeCatalog()).thenReturn(catalog);

        AppliedCovenant uses = applied("ac-1", AppliedCovenantStatus.ACTIVE, AssessmentType.AUTO,
                "DPS_EBITDA / DPS_INTEREST >= 2", "X >= 1");
        AppliedCovenant other = applied("ac-2", AppliedCovenantStatus.ACTIVE, AssessmentType.AUTO, null, "DSCR >= 1");
        AppliedCovenant suspended = applied("ac-3", AppliedCovenantStatus.SUSPENDED, AssessmentType.AUTO, null, "a");
        AppliedCovenant manual = applied("ac-4", AppliedCovenantStatus.ACTIVE, AssessmentType.MANUAL, null, "text");
        AppliedCovenant broken = applied("ac-5", AppliedCovenantStatus.ACTIVE, AssessmentType.AUTO, " ", "BAD(");
        AppliedCovenant failing = applied("ac-6", AppliedCovenantStatus.ACTIVE, AssessmentType.AUTO, null,
                "DPS_EBITDA > 0");
        when(appliedRepo.findByCpIdInOrderByIdAsc(List.of("CP-1")))
                .thenReturn(List.of(uses, other, suspended, manual, broken, failing));
        CompiledFormula ebitda = compiled("DPS_EBITDA");
        CompiledFormula dscr = compiled("DSCR");
        CompiledFormula ebitda2 = compiled("DPS_EBITDA");
        when(formulaEngine.compile("DPS_EBITDA / DPS_INTEREST >= 2", catalog)).thenReturn(ebitda);
        when(formulaEngine.compile("DSCR >= 1", catalog)).thenReturn(dscr);
        when(formulaEngine.compile("BAD(", catalog)).thenThrow(new FormulaException("bad"));
        when(formulaEngine.compile("DPS_EBITDA > 0", catalog)).thenReturn(ebitda2);
        lenient().when(assessmentService.runAssessment(
                eq("ac-6"), eq(AssessmentTrigger.MANUAL), eq("data-event"), any()))
                .thenThrow(new RuntimeException("boom\n"));

        DataEventResponse out = service.handle(req(TableEntityKeyLevel.CP_ID, "CP-1"));

        assertThat(out).isEqualTo(new DataEventResponse(1, false));
        verify(ingestion).recordSourceChange(t, "CP-1", CHANGED);
        verify(assessmentService).runAssessment(eq("ac-1"), eq(AssessmentTrigger.MANUAL), eq("data-event"), any());
        verify(assessmentService, never()).runAssessment(eq("ac-2"), any(), any(), any());
        ArgumentCaptor<DataEvent> saved = ArgumentCaptor.forClass(DataEvent.class);
        verify(eventRepo).save(saved.capture());
        assertThat(saved.getValue().getAssessmentsQueued()).isEqualTo(1);
        assertThat(saved.getValue().getDocumentId()).isEqualTo("doc-1");
        assertThat(saved.getValue().getReceivedBy()).isEqualTo("system");
        assertThat(saved.getValue().getEntityKeyLevel()).isEqualTo(TableEntityKeyLevel.CP_ID);
        assertThat(saved.getValue().getAsOfDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(saved.getValue().getChangedAt()).isEqualTo(CHANGED);
        assertThat(saved.getValue().getDataSourceId()).isEqualTo("ds-dps");
        assertThat(saved.getValue().getEntityKey()).isEqualTo("CP-1");
        assertThat(saved.getValue().getReceivedAt()).isNotNull();
        assertThat(saved.getValue().getId()).isNull();
    }

    @Test
    void obligorEventFansOutToMappedCps() {
        when(tableRepo.findByDataSourceIdOrderByNameAsc("ds-dps"))
                .thenReturn(List.of(table("t2", TableEntityKeyLevel.OBLIGOR_ID)));
        when(measureRepo.findByDataSourceTableId("t2")).thenReturn(List.of(measure("GRP_EBITDA")));
        when(obligorRepo.findCpIdsByObligorId("OBL-1")).thenReturn(List.of());
        assertThat(service.handle(req(TableEntityKeyLevel.OBLIGOR_ID, "OBL-1")).assessmentsQueued()).isZero();
        verify(appliedRepo, never()).findByCpIdInOrderByIdAsc(any());
    }

    @Test
    void tableWithoutMeasuresReassessesNothing() {
        when(tableRepo.findByDataSourceIdOrderByNameAsc("ds-dps"))
                .thenReturn(List.of(table("t3", TableEntityKeyLevel.CP_ID)));
        when(measureRepo.findByDataSourceTableId("t3")).thenReturn(List.of());
        assertThat(service.handle(req(TableEntityKeyLevel.CP_ID, "CP-1")).assessmentsQueued()).isZero();
    }
}
