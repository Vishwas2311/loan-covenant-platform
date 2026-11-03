package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.exception.DuplicateResourceException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.repository.DataSourceTableRepository;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.formula.FunctionKind;
import com.crisil.covenant.dto.CreateMeasureRequest;
import com.crisil.covenant.dto.MeasureResponse;
import com.crisil.covenant.dto.UpdateMeasureRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.repository.MeasureRepository;

@ExtendWith(MockitoExtension.class)
class MeasureServiceTest {

    @Mock private MeasureRepository repository;
    @Mock private DataSourceTableRepository tableRepository;
    @InjectMocks private MeasureService service;

    private DataSourceTable table(String id) {
        DataSource ds = new DataSource();
        ReflectionTestUtils.setField(ds, "id", "7");
        DataSourceTable t = new DataSourceTable();
        ReflectionTestUtils.setField(t, "id", id);
        t.setDataSource(ds);
        t.setTableName("cp_financials");
        return t;
    }

    private DataSourceTable keyValueTable(String id) {
        DataSourceTable t = table(id);
        t.setTableName("cp_ratios_eav");
        t.setLayout(TableLayout.KEY_VALUE);
        t.setCodeColumn("ratio_code");
        t.setValueColumn("ratio_value");
        return t;
    }

    private Measure existing(String code) {
        Measure m = new Measure();
        m.setCode(code);
        m.setName("name");
        m.setDataSourceTable(table("5"));
        m.setSourceColumn("dscr");
        m.setDefaultAggregation(FunctionKind.LATEST);
        m.setDataType(DataColumnType.NUMBER);
        return m;
    }

    private void echoSave() {
        when(repository.saveAndFlush(any(Measure.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateMeasureRequest createRequest(String code) {
        return new CreateMeasureRequest(code, "Profit After Tax", "desc", "5", "pat_cr",
                FunctionKind.AVG, DataColumnType.NUMBER, "Cr", null);
    }

    @Test
    void createPersistsMeasure() {
        when(repository.existsByCodeIgnoreCase("PAT")).thenReturn(false);
        when(tableRepository.findById("5")).thenReturn(Optional.of(table("5")));
        echoSave();

        MeasureResponse r = service.create(createRequest("PAT"));
        assertThat(r.code()).isEqualTo("PAT");
        assertThat(r.sourceColumn()).isEqualTo("pat_cr");
        assertThat(r.defaultAggregation()).isEqualTo(FunctionKind.AVG);
        assertThat(r.unit()).isEqualTo("Cr");
        assertThat(r.active()).isTrue();
        assertThat(r.dataSourceTableId()).isEqualTo("5");
        assertThat(r.dataSourceId()).isEqualTo("7");
    }

    @Test
    void createRejectsDuplicateCode() {
        when(repository.existsByCodeIgnoreCase("PAT")).thenReturn(true);
        assertThatThrownBy(() -> service.create(createRequest("PAT")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void createRejectsMissingTable() {
        when(repository.existsByCodeIgnoreCase("PAT")).thenReturn(false);
        when(tableRepository.findById("5")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(createRequest("PAT")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createBlankUnitStoredAsNull() {
        when(repository.existsByCodeIgnoreCase("PAT")).thenReturn(false);
        when(tableRepository.findById("5")).thenReturn(Optional.of(table("5")));
        echoSave();
        CreateMeasureRequest req = new CreateMeasureRequest("PAT", "n", null, "5", "pat_cr",
                FunctionKind.AVG, DataColumnType.NUMBER, "  ", null);
        assertThat(service.create(req).unit()).isNull();
    }

    @Test
    void createOnKeyValueTableUsesSourceCodeAndClearsColumn() {
        when(repository.existsByCodeIgnoreCase("DSCR")).thenReturn(false);
        when(tableRepository.findById("5")).thenReturn(Optional.of(keyValueTable("5")));
        echoSave();
        // A stray sourceColumn is sent too — it must be cleared for a KEY_VALUE measure.
        CreateMeasureRequest req = new CreateMeasureRequest("DSCR", "Debt Service", "d", "5", "stray_col",
                FunctionKind.LATEST, DataColumnType.NUMBER, null, "DSCR");
        MeasureResponse r = service.create(req);
        assertThat(r.sourceCode()).isEqualTo("DSCR");
        assertThat(r.sourceColumn()).isNull();
    }

    @Test
    void createOnKeyValueTableRequiresSourceCode() {
        when(repository.existsByCodeIgnoreCase("DSCR")).thenReturn(false);
        when(tableRepository.findById("5")).thenReturn(Optional.of(keyValueTable("5")));
        CreateMeasureRequest req = new CreateMeasureRequest("DSCR", "n", null, "5", "dscr",
                FunctionKind.LATEST, DataColumnType.NUMBER, null, "  ");
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("source code");
    }

    @Test
    void createOnColumnarTableRequiresSourceColumn() {
        when(repository.existsByCodeIgnoreCase("PAT")).thenReturn(false);
        when(tableRepository.findById("5")).thenReturn(Optional.of(table("5")));
        CreateMeasureRequest req = new CreateMeasureRequest("PAT", "n", null, "5", "  ",
                FunctionKind.AVG, DataColumnType.NUMBER, null, "PAT");
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("source column");
    }

    @Test
    void createOnColumnarTableClearsStraySourceCode() {
        when(repository.existsByCodeIgnoreCase("PAT")).thenReturn(false);
        when(tableRepository.findById("5")).thenReturn(Optional.of(table("5")));
        echoSave();
        CreateMeasureRequest req = new CreateMeasureRequest("PAT", "n", null, "5", "pat_cr",
                FunctionKind.AVG, DataColumnType.NUMBER, null, "STRAY");
        MeasureResponse r = service.create(req);
        assertThat(r.sourceColumn()).isEqualTo("pat_cr");
        assertThat(r.sourceCode()).isNull();
    }

    @Test
    void updateChangesFields() {
        Measure m = existing("PAT");
        when(repository.findById("1")).thenReturn(Optional.of(m));
        when(tableRepository.findById("5")).thenReturn(Optional.of(table("5")));
        echoSave();
        UpdateMeasureRequest req = new UpdateMeasureRequest("PAT", "Renamed", "d2", "5", "pat_cr",
                FunctionKind.SUM, DataColumnType.NUMBER, null, null);
        MeasureResponse r = service.update("1", req);
        assertThat(r.name()).isEqualTo("Renamed");
        assertThat(r.defaultAggregation()).isEqualTo(FunctionKind.SUM);
    }

    @Test
    void updateSameCodeSkipsDuplicateCheck() {
        Measure m = existing("PAT");
        when(repository.findById("1")).thenReturn(Optional.of(m));
        when(tableRepository.findById("5")).thenReturn(Optional.of(table("5")));
        echoSave();
        UpdateMeasureRequest req = new UpdateMeasureRequest("pat", "n", null, "5", "pat_cr",
                FunctionKind.AVG, DataColumnType.NUMBER, null, null);
        assertThat(service.update("1", req).code()).isEqualTo("pat");
    }

    @Test
    void updateRejectsRenameToExistingCode() {
        Measure m = existing("PAT");
        when(repository.findById("1")).thenReturn(Optional.of(m));
        when(repository.existsByCodeIgnoreCase("DSCR")).thenReturn(true);
        UpdateMeasureRequest req = new UpdateMeasureRequest("DSCR", "n", null, "5", "dscr",
                FunctionKind.LATEST, DataColumnType.NUMBER, null, null);
        assertThatThrownBy(() -> service.update("1", req))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void updateMissingMeasureThrows() {
        when(repository.findById("9")).thenReturn(Optional.empty());
        UpdateMeasureRequest req = new UpdateMeasureRequest("PAT", "n", null, "5", "pat_cr",
                FunctionKind.AVG, DataColumnType.NUMBER, null, null);
        assertThatThrownBy(() -> service.update("9", req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void setActiveTogglesFlag() {
        Measure m = existing("PAT");
        when(repository.findById("1")).thenReturn(Optional.of(m));
        echoSave();
        assertThat(service.setActive("1", false).active()).isFalse();
    }

    @Test
    void getReturnsResponseOrThrows() {
        Measure m = existing("PAT");
        when(repository.findById("1")).thenReturn(Optional.of(m));
        assertThat(service.get("1").code()).isEqualTo("PAT");

        when(repository.findById("9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("9")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listActiveOnlyUsesActiveQuery() {
        when(repository.findByActiveTrueOrderByCodeAsc()).thenReturn(List.of(existing("PAT")));
        assertThat(service.list(true)).hasSize(1);
    }

    @Test
    void listAllUsesFullQueryForFalseAndNull() {
        when(repository.findAllByOrderByCodeAsc()).thenReturn(List.of(existing("PAT"), existing("DSCR")));
        assertThat(service.list(false)).hasSize(2);
        assertThat(service.list(null)).hasSize(2);
    }
}
