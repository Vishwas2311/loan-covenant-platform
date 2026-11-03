package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.exception.DuplicateResourceException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.dto.CreateDataSourceTableRequest;
import com.crisil.covenant.dto.DataSourceTableResponse;
import com.crisil.covenant.dto.UpdateDataSourceTableRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.crisil.covenant.domain.DataColumn;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.DataSourceType;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.repository.DataSourceRepository;
import com.crisil.covenant.repository.DataSourceTableRepository;

@ExtendWith(MockitoExtension.class)
class DataSourceTableServiceTest {

    @Mock private DataSourceTableRepository repository;
    @Mock private DataSourceRepository dataSourceRepository;
    @Mock private ColumnDiscoveryService columnDiscovery;
    @InjectMocks private DataSourceTableService service;

    private DataSource dataSource(String id) {
        DataSource ds = new DataSource();
        ReflectionTestUtils.setField(ds, "id", id);
        ds.setName("Demo MSSQL");
        ds.setType(DataSourceType.MSSQL);
        return ds;
    }

    private DataSourceTable table(String id, String dataSourceId) {
        DataSourceTable t = new DataSourceTable();
        ReflectionTestUtils.setField(t, "id", id);
        t.setDataSource(dataSource(dataSourceId));
        t.setName("Financials");
        t.setTableName("cp_financials_demo");
        t.setEntityKeyColumn("cp_id");
        t.setAsOfColumn("as_of_date");
        return t;
    }

    private CreateDataSourceTableRequest createReq() {
        return new CreateDataSourceTableRequest("Financials", "public", "cp_financials_demo",
                "cp_id", "as_of_date", null, null, null, null);
    }

    private void echoSave() {
        when(repository.saveAndFlush(any(DataSourceTable.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createPersistsTable() {
        when(dataSourceRepository.findById("1")).thenReturn(Optional.of(dataSource("1")));
        when(repository.existsByDataSourceIdAndNameIgnoreCase("1", "Financials")).thenReturn(false);
        echoSave();
        DataSourceTableResponse r = service.create("1", createReq());
        assertThat(r.tableName()).isEqualTo("cp_financials_demo");
        assertThat(r.entityKeyColumn()).isEqualTo("cp_id");
        assertThat(r.asOfColumn()).isEqualTo("as_of_date");
        assertThat(r.schemaName()).isEqualTo("public");
        assertThat(r.active()).isTrue();
    }

    @Test
    void createBlankSchemaStoredAsNull() {
        when(dataSourceRepository.findById("1")).thenReturn(Optional.of(dataSource("1")));
        when(repository.existsByDataSourceIdAndNameIgnoreCase(any(), any())).thenReturn(false);
        echoSave();
        CreateDataSourceTableRequest req = new CreateDataSourceTableRequest(
                "Financials", "", "cp_financials_demo", "cp_id", "as_of_date", null, null, null, null);
        assertThat(service.create("1", req).schemaName()).isNull();
    }

    @Test
    void createNullLayoutDefaultsToColumnar() {
        when(dataSourceRepository.findById("1")).thenReturn(Optional.of(dataSource("1")));
        when(repository.existsByDataSourceIdAndNameIgnoreCase("1", "Financials")).thenReturn(false);
        echoSave();
        assertThat(service.create("1", createReq()).layout()).isEqualTo(TableLayout.COLUMNAR);
    }

    @Test
    void createKeyValueTablePersistsCodeAndValueColumns() {
        when(dataSourceRepository.findById("1")).thenReturn(Optional.of(dataSource("1")));
        when(repository.existsByDataSourceIdAndNameIgnoreCase("1", "Ratios")).thenReturn(false);
        echoSave();
        CreateDataSourceTableRequest req = new CreateDataSourceTableRequest("Ratios", "public",
                "cp_ratios_eav", "cp_id", "as_of_date", TableLayout.KEY_VALUE, "ratio_code", "ratio_value", null);
        DataSourceTableResponse r = service.create("1", req);
        assertThat(r.layout()).isEqualTo(TableLayout.KEY_VALUE);
        assertThat(r.codeColumn()).isEqualTo("ratio_code");
        assertThat(r.valueColumn()).isEqualTo("ratio_value");
    }

    @Test
    void createKeyValueTableRequiresCodeAndValueColumns() {
        when(dataSourceRepository.findById("1")).thenReturn(Optional.of(dataSource("1")));
        when(repository.existsByDataSourceIdAndNameIgnoreCase("1", "Ratios")).thenReturn(false);
        CreateDataSourceTableRequest req = new CreateDataSourceTableRequest("Ratios", "public",
                "cp_ratios_eav", "cp_id", "as_of_date", TableLayout.KEY_VALUE, "ratio_code", null, null);
        assertThatThrownBy(() -> service.create("1", req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("KEY_VALUE");
    }

    @Test
    void createColumnarTableRejectsCodeOrValueColumns() {
        when(dataSourceRepository.findById("1")).thenReturn(Optional.of(dataSource("1")));
        when(repository.existsByDataSourceIdAndNameIgnoreCase("1", "Financials")).thenReturn(false);
        CreateDataSourceTableRequest req = new CreateDataSourceTableRequest("Financials", "public",
                "cp_financials_demo", "cp_id", "as_of_date", TableLayout.COLUMNAR, "ratio_code", null, null);
        assertThatThrownBy(() -> service.create("1", req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("COLUMNAR");
    }

    @Test
    void codeValuesDelegatesToDiscovery() {
        DataSourceTable t = table("2", "1");
        t.setLayout(TableLayout.KEY_VALUE);
        t.setCodeColumn("ratio_code");
        t.setValueColumn("ratio_value");
        when(repository.findById("2")).thenReturn(Optional.of(t));
        when(columnDiscovery.discoverCodeValues(t.getDataSource(), t)).thenReturn(List.of("DSCR", "PAT"));
        assertThat(service.codeValues("1", "2")).containsExactly("DSCR", "PAT");
    }

    @Test
    void createRejectsMissingDataSource() {
        when(dataSourceRepository.findById("1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create("1", createReq()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createRejectsDuplicateName() {
        when(dataSourceRepository.findById("1")).thenReturn(Optional.of(dataSource("1")));
        when(repository.existsByDataSourceIdAndNameIgnoreCase("1", "Financials")).thenReturn(true);
        assertThatThrownBy(() -> service.create("1", createReq()))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void updateChangesFields() {
        when(repository.findById("2")).thenReturn(Optional.of(table("2", "1")));
        echoSave();
        UpdateDataSourceTableRequest req = new UpdateDataSourceTableRequest(
                "Renamed", null, "other_table", "cif", "snapshot_date", null, null, null,
                TableEntityKeyLevel.OBLIGOR_ID);
        DataSourceTableResponse r = service.update("1", "2", req);
        assertThat(r.name()).isEqualTo("Renamed");
        assertThat(r.entityKeyColumn()).isEqualTo("cif");
        assertThat(r.entityKeyLevel()).isEqualTo(TableEntityKeyLevel.OBLIGOR_ID);
    }

    @Test
    void updateRejectsRenameToExistingName() {
        when(repository.findById("2")).thenReturn(Optional.of(table("2", "1")));
        when(repository.existsByDataSourceIdAndNameIgnoreCase("1", "Taken")).thenReturn(true);
        UpdateDataSourceTableRequest req = new UpdateDataSourceTableRequest(
                "Taken", null, "t", "cif", "d", null, null, null, null);
        assertThatThrownBy(() -> service.update("1", "2", req))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void updateRejectsTableFromOtherDataSource() {
        when(repository.findById("2")).thenReturn(Optional.of(table("2", "99")));
        UpdateDataSourceTableRequest req = new UpdateDataSourceTableRequest(
                "Financials", null, "t", "cif", "d", null, null, null, null);
        assertThatThrownBy(() -> service.update("1", "2", req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateRejectsMissingTable() {
        when(repository.findById("2")).thenReturn(Optional.empty());
        UpdateDataSourceTableRequest req = new UpdateDataSourceTableRequest(
                "Financials", null, "t", "cif", "d", null, null, null, null);
        assertThatThrownBy(() -> service.update("1", "2", req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void setActiveTogglesFlag() {
        when(repository.findById("2")).thenReturn(Optional.of(table("2", "1")));
        echoSave();
        assertThat(service.setActive("1", "2", false).active()).isFalse();
    }

    @Test
    void listReturnsTables() {
        when(dataSourceRepository.findById("1")).thenReturn(Optional.of(dataSource("1")));
        when(repository.findByDataSourceIdOrderByNameAsc("1")).thenReturn(List.of(table("2", "1")));
        assertThat(service.list("1")).hasSize(1);
    }

    @Test
    void listRejectsMissingDataSource() {
        when(dataSourceRepository.findById("1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list("1")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getReturnsTable() {
        when(repository.findById("2")).thenReturn(Optional.of(table("2", "1")));
        assertThat(service.get("1", "2").tableName()).isEqualTo("cp_financials_demo");
    }

    @Test
    void columnsDelegatesToDiscovery() {
        DataSourceTable t = table("2", "1");
        when(repository.findById("2")).thenReturn(Optional.of(t));
        when(columnDiscovery.discoverColumns(t.getDataSource(), t))
                .thenReturn(List.of(DataColumn.of("dscr", "dscr", DataColumnType.NUMBER)));
        assertThat(service.columns("1", "2")).hasSize(1);
    }

    @Test
    void requireEntityReturnsOrThrows() {
        DataSourceTable t = table("2", "1");
        when(repository.findById("2")).thenReturn(Optional.of(t));
        assertThat(service.requireEntity("2")).isSameAs(t);

        when(repository.findById("3")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireEntity("3")).isInstanceOf(ResourceNotFoundException.class);
    }
}
