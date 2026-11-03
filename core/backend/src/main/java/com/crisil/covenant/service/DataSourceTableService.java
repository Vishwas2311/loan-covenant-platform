package com.crisil.covenant.service;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.exception.DuplicateResourceException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.dto.CreateDataSourceTableRequest;
import com.crisil.covenant.dto.DataSourceTableResponse;
import com.crisil.covenant.dto.UpdateDataSourceTableRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crisil.covenant.domain.DataColumn;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.enums.TableEntityKeyLevel;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.repository.DataSourceRepository;
import com.crisil.covenant.repository.DataSourceTableRepository;

/** CRUD for the tables that live under a {@link DataSource}, plus per-table column discovery. */
@Service
@Transactional
public class DataSourceTableService {

    private final DataSourceTableRepository repository;
    private final DataSourceRepository dataSourceRepository;
    private final ColumnDiscoveryService columnDiscovery;

    public DataSourceTableService(DataSourceTableRepository repository,
                                  DataSourceRepository dataSourceRepository,
                                  ColumnDiscoveryService columnDiscovery) {
        this.repository = repository;
        this.dataSourceRepository = dataSourceRepository;
        this.columnDiscovery = columnDiscovery;
    }

    public DataSourceTableResponse create(String dataSourceId, CreateDataSourceTableRequest request) {
        DataSource source = requireDataSource(dataSourceId);
        if (repository.existsByDataSourceIdAndNameIgnoreCase(dataSourceId, request.name())) {
            throw new DuplicateResourceException(
                    "Table '" + request.name() + "' already exists on this data source");
        }
        DataSourceTable table = new DataSourceTable();
        table.setDataSource(source);
        apply(table, request.name(), request.schemaName(), request.tableName(),
                request.entityKeyColumn(), request.asOfColumn(),
                request.layout(), request.codeColumn(), request.valueColumn(),
                request.entityKeyLevel());
        table.setActive(true);
        return DataSourceTableResponse.from(repository.saveAndFlush(table));
    }

    public DataSourceTableResponse update(String dataSourceId, String tableId,
                                          UpdateDataSourceTableRequest request) {
        DataSourceTable table = requireTable(dataSourceId, tableId);
        if (!table.getName().equalsIgnoreCase(request.name())
                && repository.existsByDataSourceIdAndNameIgnoreCase(dataSourceId, request.name())) {
            throw new DuplicateResourceException(
                    "Table '" + request.name() + "' already exists on this data source");
        }
        apply(table, request.name(), request.schemaName(), request.tableName(),
                request.entityKeyColumn(), request.asOfColumn(),
                request.layout(), request.codeColumn(), request.valueColumn(),
                request.entityKeyLevel());
        return DataSourceTableResponse.from(repository.saveAndFlush(table));
    }

    public DataSourceTableResponse setActive(String dataSourceId, String tableId, boolean active) {
        DataSourceTable table = requireTable(dataSourceId, tableId);
        table.setActive(active);
        return DataSourceTableResponse.from(repository.saveAndFlush(table));
    }

    @Transactional(readOnly = true)
    public List<DataSourceTableResponse> list(String dataSourceId) {
        requireDataSource(dataSourceId);
        return repository.findByDataSourceIdOrderByNameAsc(dataSourceId).stream()
                .map(DataSourceTableResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public DataSourceTableResponse get(String dataSourceId, String tableId) {
        return DataSourceTableResponse.from(requireTable(dataSourceId, tableId));
    }

    @Transactional(readOnly = true)
    public List<DataColumn> columns(String dataSourceId, String tableId) {
        DataSourceTable table = requireTable(dataSourceId, tableId);
        return columnDiscovery.discoverColumns(table.getDataSource(), table);
    }

    /**
     * The distinct measure codes physically present in a {@code KEY_VALUE} table's code column, so an
     * admin can pick a measure's {@code sourceCode} from a list instead of typing it blindly.
     */
    @Transactional(readOnly = true)
    public List<String> codeValues(String dataSourceId, String tableId) {
        DataSourceTable table = requireTable(dataSourceId, tableId);
        return columnDiscovery.discoverCodeValues(table.getDataSource(), table);
    }

    /** Entity lookup for other services (e.g. the aggregation resolver). */
    @Transactional(readOnly = true)
    public DataSourceTable requireEntity(String tableId) {
        return repository.findById(tableId)
                .orElseThrow(() -> new ResourceNotFoundException("Data source table " + tableId + " not found"));
    }

    private static void apply(DataSourceTable table, String name, String schemaName, String tableName,
                              String entityKeyColumn, String asOfColumn, TableLayout layout,
                              String codeColumn, String valueColumn, TableEntityKeyLevel entityKeyLevel) {
        table.setName(name);
        table.setSchemaName(schemaName == null || schemaName.isBlank() ? null : schemaName);
        table.setTableName(tableName);
        table.setEntityKeyColumn(entityKeyColumn);
        table.setAsOfColumn(asOfColumn);
        table.setEntityKeyLevel(entityKeyLevel == null ? TableEntityKeyLevel.CP_ID : entityKeyLevel);

        TableLayout effectiveLayout = layout == null ? TableLayout.COLUMNAR : layout;
        table.setLayout(effectiveLayout);
        String code = blankToNull(codeColumn);
        String value = blankToNull(valueColumn);
        if (effectiveLayout == TableLayout.KEY_VALUE) {
            if (code == null || value == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TABLE_LAYOUT",
                        "KEY_VALUE tables require both a code column and a value column");
            }
        } else {
            if (code != null || value != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TABLE_LAYOUT",
                        "COLUMNAR tables must not define a code column or value column");
            }
        }
        table.setCodeColumn(code);
        table.setValueColumn(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private DataSource requireDataSource(String dataSourceId) {
        return dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Data source " + dataSourceId + " not found"));
    }

    private DataSourceTable requireTable(String dataSourceId, String tableId) {
        DataSourceTable table = repository.findById(tableId)
                .orElseThrow(() -> new ResourceNotFoundException("Data source table " + tableId + " not found"));
        if (table.getDataSource() == null || !table.getDataSource().getId().equals(dataSourceId)) {
            throw new ResourceNotFoundException(
                    "Table " + tableId + " does not belong to data source " + dataSourceId);
        }
        return table;
    }
}
