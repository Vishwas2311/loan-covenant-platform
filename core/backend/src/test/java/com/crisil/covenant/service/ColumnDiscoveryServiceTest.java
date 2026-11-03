package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.service.DataCatalogClient;
import com.crisil.covenant.domain.DataCatalogResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.crisil.covenant.domain.DataColumn;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.DataSourceType;
import com.crisil.covenant.enums.TableLayout;

@ExtendWith(MockitoExtension.class)
class ColumnDiscoveryServiceTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:coldiscover;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Mock private DataCatalogClient catalogClient;

    @BeforeAll
    static void seedTable() {
        DataSource raw = new DriverManagerDataSource(JDBC_URL);
        JdbcTemplate jdbc = new JdbcTemplate(raw);
        jdbc.execute("DROP TABLE IF EXISTS metrics");
        jdbc.execute("CREATE TABLE metrics ("
                + "cp_id VARCHAR(32), "
                + "as_of DATE, "
                + "dscr DECIMAL(10,2), "
                + "auditor VARCHAR(64), "
                + "insurance_active BOOLEAN)");

        // KEY_VALUE (EAV) table for code-value discovery.
        jdbc.execute("DROP TABLE IF EXISTS ratios_eav");
        jdbc.execute("CREATE TABLE ratios_eav ("
                + "cp_id VARCHAR(32), as_of DATE, ratio_code VARCHAR(32), ratio_value DECIMAL(10,2))");
        jdbc.update("INSERT INTO ratios_eav VALUES ('CP-1','2026-01-01','DSCR',1.3)");
        jdbc.update("INSERT INTO ratios_eav VALUES ('CP-1','2026-01-01','PAT',0.5)");
        jdbc.update("INSERT INTO ratios_eav VALUES ('CP-1','2026-01-01','ICR',2.1)");
        jdbc.update("INSERT INTO ratios_eav VALUES ('CP-2','2026-01-01','DSCR',1.8)"); // duplicate code
        jdbc.update("INSERT INTO ratios_eav VALUES ('CP-2','2026-01-01',NULL,9.9)");   // null code excluded
    }

    private com.crisil.covenant.entity.DataSource mssqlSource(Map<String, String> extra) {
        com.crisil.covenant.entity.DataSource ds = new com.crisil.covenant.entity.DataSource();
        ds.setName("MSSQL");
        ds.setType(DataSourceType.MSSQL);
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("jdbcUrl", JDBC_URL);
        cfg.put("tableName", "metrics");
        cfg.put("cpIdColumn", "cp_id");
        cfg.put("asOfColumn", "as_of");
        cfg.putAll(extra);
        ds.setConnectionConfig(cfg);
        return ds;
    }

    @Test
    void restDiscoveryMapsCatalogColumnsAndDefaultsLabel() {
        ColumnDiscoveryService svc = new ColumnDiscoveryService(catalogClient);
        when(catalogClient.fetchCatalog()).thenReturn(new DataCatalogResponse(List.of(
                new DataCatalogResponse.Column("DSCR", "Debt Service", "number", "metric"),
                new DataCatalogResponse.Column("auditor", "  ", "string", "metric"))));

        com.crisil.covenant.entity.DataSource rest =
                new com.crisil.covenant.entity.DataSource();
        rest.setName("REST");
        rest.setType(DataSourceType.REST);

        List<DataColumn> cols = svc.discover(rest);
        assertThat(cols).hasSize(2);
        assertThat(cols.get(0).key()).isEqualTo("DSCR");
        assertThat(cols.get(0).label()).isEqualTo("Debt Service");
        assertThat(cols.get(0).dataType()).isEqualTo(DataColumnType.NUMBER);
        // Blank label falls back to the key.
        assertThat(cols.get(1).label()).isEqualTo("auditor");
    }

    @Test
    void mssqlDiscoveryIntrospectsAndExcludesPlumbingColumns() {
        ColumnDiscoveryService svc = new ColumnDiscoveryService(catalogClient);
        List<DataColumn> cols = svc.discover(mssqlSource(Map.of()));
        List<String> keys = cols.stream().map(DataColumn::key).toList();
        assertThat(keys).contains("dscr", "auditor", "insurance_active");
        // cp_id and as_of are plumbing and must be excluded.
        assertThat(keys).doesNotContain("cp_id", "as_of");
    }

    @Test
    void mssqlDiscoveryWithoutAsOfKeepsAllButCpId() {
        ColumnDiscoveryService svc = new ColumnDiscoveryService(catalogClient);
        Map<String, String> noAsOf = new LinkedHashMap<>();
        noAsOf.put("asOfColumn", "");
        List<DataColumn> cols = svc.discover(mssqlSource(noAsOf));
        List<String> keys = cols.stream().map(DataColumn::key).toList();
        assertThat(keys).contains("as_of");
        assertThat(keys).doesNotContain("cp_id");
    }

    @Test
    void mssqlMissingTableNameThrows() {
        ColumnDiscoveryService svc = new ColumnDiscoveryService(catalogClient);
        com.crisil.covenant.entity.DataSource ds =
                new com.crisil.covenant.entity.DataSource();
        ds.setName("Broken");
        ds.setType(DataSourceType.MSSQL);
        ds.setConnectionConfig(Map.of("jdbcUrl", JDBC_URL));
        assertThatThrownBy(() -> svc.discover(ds))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("tableName");
    }

    @Test
    void restDiscoveryFallsBackToKeyWhenLabelNull() {
        ColumnDiscoveryService svc = new ColumnDiscoveryService(catalogClient);
        when(catalogClient.fetchCatalog()).thenReturn(new DataCatalogResponse(List.of(
                new DataCatalogResponse.Column("DSCR", null, "number", "metric"))));
        com.crisil.covenant.entity.DataSource rest =
                new com.crisil.covenant.entity.DataSource();
        rest.setName("REST");
        rest.setType(DataSourceType.REST);
        List<DataColumn> cols = svc.discover(rest);
        assertThat(cols).hasSize(1);
        assertThat(cols.get(0).label()).isEqualTo("DSCR");
    }

    @Test
    void mssqlDiscoveryWithCredentialsAndSchema() {
        String url = "jdbc:h2:mem:coldisc_creds;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource credDs = new DriverManagerDataSource(url);
        credDs.setUsername("sa");
        credDs.setPassword("secret");
        JdbcTemplate jdbc = new JdbcTemplate(credDs);
        jdbc.execute("DROP TABLE IF EXISTS metrics");
        jdbc.execute("CREATE TABLE metrics (cp_id VARCHAR(32), dscr DECIMAL(10,2), auditor VARCHAR(64))");

        ColumnDiscoveryService svc = new ColumnDiscoveryService(catalogClient);
        com.crisil.covenant.entity.DataSource ds =
                new com.crisil.covenant.entity.DataSource();
        ds.setName("MSSQL creds");
        ds.setType(DataSourceType.MSSQL);
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("jdbcUrl", url);
        cfg.put("tableName", "metrics");
        cfg.put("cpIdColumn", "cp_id");
        cfg.put("schema", "public");
        cfg.put("username", "sa");
        cfg.put("password", "secret");
        ds.setConnectionConfig(cfg);

        List<DataColumn> cols = svc.discover(ds);
        List<String> keys = cols.stream().map(DataColumn::key).toList();
        assertThat(keys).contains("dscr", "auditor");
        assertThat(keys).doesNotContain("cp_id");
    }

    private DataSourceTable metricsTable() {
        DataSourceTable t = new DataSourceTable();
        t.setName("Metrics");
        t.setTableName("metrics");
        t.setEntityKeyColumn("cp_id");
        t.setAsOfColumn("as_of");
        return t;
    }

    private DataSourceTable ratiosEavTable() {
        DataSourceTable t = new DataSourceTable();
        t.setName("Ratios");
        t.setTableName("ratios_eav");
        t.setEntityKeyColumn("cp_id");
        t.setAsOfColumn("as_of");
        t.setLayout(TableLayout.KEY_VALUE);
        t.setCodeColumn("ratio_code");
        t.setValueColumn("ratio_value");
        return t;
    }

    @Test
    void discoverTablesListsBaseTables() {
        ColumnDiscoveryService svc = new ColumnDiscoveryService(catalogClient);
        List<String> tables = svc.discoverTables(mssqlSource(Map.of("schema", "public")));
        assertThat(tables).contains("metrics");
    }

    @Test
    void discoverColumnsForTableExcludesKeyAndAsOf() {
        ColumnDiscoveryService svc = new ColumnDiscoveryService(catalogClient);
        List<DataColumn> cols = svc.discoverColumns(mssqlSource(Map.of()), metricsTable());
        List<String> keys = cols.stream().map(DataColumn::key).toList();
        assertThat(keys).contains("dscr", "auditor", "insurance_active");
        assertThat(keys).doesNotContain("cp_id", "as_of");
    }

    @Test
    void discoverColumnsHonoursSchema() {
        ColumnDiscoveryService svc = new ColumnDiscoveryService(catalogClient);
        DataSourceTable table = metricsTable();
        table.setSchemaName("public");
        List<DataColumn> cols = svc.discoverColumns(mssqlSource(Map.of()), table);
        assertThat(cols.stream().map(DataColumn::key)).contains("dscr");
    }

    @Test
    void discoverColumnsForKeyValueSkipsCodeAndValueColumns() {
        ColumnDiscoveryService svc = new ColumnDiscoveryService(catalogClient);
        List<DataColumn> cols = svc.discoverColumns(mssqlSource(Map.of()), ratiosEavTable());
        List<String> keys = cols.stream().map(DataColumn::key).toList();
        // cp_id/as_of (plumbing) plus the KEY_VALUE code/value columns are all excluded.
        assertThat(keys).doesNotContain("cp_id", "as_of", "ratio_code", "ratio_value");
    }

    @Test
    void discoverCodeValuesReturnsDistinctNonNullCodesSorted() {
        ColumnDiscoveryService svc = new ColumnDiscoveryService(catalogClient);
        List<String> codes = svc.discoverCodeValues(mssqlSource(Map.of()), ratiosEavTable());
        assertThat(codes).containsExactly("DSCR", "ICR", "PAT");
    }

    @Test
    void discoverCodeValuesRejectsColumnarTable() {
        ColumnDiscoveryService svc = new ColumnDiscoveryService(catalogClient);
        assertThatThrownBy(() -> svc.discoverCodeValues(mssqlSource(Map.of()), metricsTable()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("KEY_VALUE");
    }

    @Test
    void mapTypeCoversAllBranches() {
        assertThat(ColumnDiscoveryService.mapType(null)).isEqualTo(DataColumnType.STRING);
        assertThat(ColumnDiscoveryService.mapType("")).isEqualTo(DataColumnType.STRING);
        assertThat(ColumnDiscoveryService.mapType("BIT")).isEqualTo(DataColumnType.BOOLEAN);
        assertThat(ColumnDiscoveryService.mapType("boolean")).isEqualTo(DataColumnType.BOOLEAN);
        assertThat(ColumnDiscoveryService.mapType("VARCHAR")).isEqualTo(DataColumnType.STRING);
        assertThat(ColumnDiscoveryService.mapType("text")).isEqualTo(DataColumnType.STRING);
        assertThat(ColumnDiscoveryService.mapType("CLOB")).isEqualTo(DataColumnType.STRING);
        assertThat(ColumnDiscoveryService.mapType("string")).isEqualTo(DataColumnType.STRING);
        assertThat(ColumnDiscoveryService.mapType("DATE")).isEqualTo(DataColumnType.DATE);
        assertThat(ColumnDiscoveryService.mapType("datetime")).isEqualTo(DataColumnType.DATE);
        assertThat(ColumnDiscoveryService.mapType("INT")).isEqualTo(DataColumnType.NUMBER);
        assertThat(ColumnDiscoveryService.mapType("DECIMAL")).isEqualTo(DataColumnType.NUMBER);
        assertThat(ColumnDiscoveryService.mapType("numeric")).isEqualTo(DataColumnType.NUMBER);
        assertThat(ColumnDiscoveryService.mapType("float")).isEqualTo(DataColumnType.NUMBER);
        assertThat(ColumnDiscoveryService.mapType("REAL")).isEqualTo(DataColumnType.NUMBER);
        assertThat(ColumnDiscoveryService.mapType("DOUBLE")).isEqualTo(DataColumnType.NUMBER);
        assertThat(ColumnDiscoveryService.mapType("money")).isEqualTo(DataColumnType.NUMBER);
        assertThat(ColumnDiscoveryService.mapType("uuid")).isEqualTo(DataColumnType.STRING);
    }
}
