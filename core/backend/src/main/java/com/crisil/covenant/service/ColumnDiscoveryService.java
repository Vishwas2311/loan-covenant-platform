package com.crisil.covenant.service;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.service.DataCatalogClient;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import com.crisil.covenant.domain.DataColumn;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.entity.DataSourceTable;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.DataSourceType;
import com.crisil.covenant.enums.TableLayout;
import com.crisil.covenant.util.DataSourceSecrets;
import com.crisil.covenant.util.JdbcUrlPolicy;
import com.crisil.covenant.util.SqlIdentifiers;

/**
 * Discovers the selectable columns for a {@link DataSource}:
 * <ul>
 *   <li>{@link DataSourceType#REST} → the Data API catalog endpoint via {@link DataCatalogClient}.</li>
 *   <li>{@link DataSourceType#MSSQL} → {@code INFORMATION_SCHEMA.COLUMNS} introspection over JDBC.</li>
 * </ul>
 * The configured CP_ID / as-of columns are excluded — they are plumbing, not data points.
 */
@Service
public class ColumnDiscoveryService {

    private final DataCatalogClient catalogClient;

    /** SQL Server hosts data sources may connect to; {@code *} (the default outside prod) allows any. */
    @Value("${covenant.data-sources.allowed-jdbc-hosts:*}")
    private List<String> allowedJdbcHosts = List.of("*");

    public ColumnDiscoveryService(DataCatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    public List<DataColumn> discover(DataSource source) {
        return switch (source.getType()) {
            case REST -> fromCatalog();
            case MSSQL -> fromMssql(source);
        };
    }

    /** List the base-table names available on an MSSQL data source (for table registration). */
    public List<String> discoverTables(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(buildDataSource(source));
        String schema = source.config("schema");
        StringBuilder sql = new StringBuilder(
                "SELECT table_name FROM information_schema.tables WHERE table_type = 'BASE TABLE'");
        List<Object> args = new ArrayList<>();
        if (schema != null && !schema.isBlank()) {
            sql.append(" AND table_schema = ?");
            args.add(schema);
        }
        sql.append(" ORDER BY table_name");
        return jdbc.query(sql.toString(), args.toArray(), (rs, rowNum) -> rs.getString(1));
    }

    /**
     * Discover the selectable columns of a specific {@link DataSourceTable}, excluding its
     * entity-key and as-of columns (those are plumbing, not data points).
     */
    public List<DataColumn> discoverColumns(DataSource source, DataSourceTable table) {
        String tableName = table.getTableName();
        String schema = table.getSchemaName();
        String keyColumn = lower(table.getEntityKeyColumn());
        String asOfColumn = lower(table.getAsOfColumn());
        // For KEY_VALUE tables the code/value columns are plumbing too, not selectable data points.
        String codeColumn = lower(table.getCodeColumn());
        String valueColumn = lower(table.getValueColumn());

        JdbcTemplate jdbc = new JdbcTemplate(buildDataSource(source));
        StringBuilder sql = new StringBuilder(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ?");
        List<Object> args = new ArrayList<>();
        args.add(tableName);
        if (schema != null && !schema.isBlank()) {
            sql.append(" AND table_schema = ?");
            args.add(schema);
        }
        sql.append(" ORDER BY ordinal_position");

        List<DataColumn> all = jdbc.query(sql.toString(), args.toArray(), (rs, rowNum) -> {
            String name = rs.getString(1);
            return new DataColumn(name, name, mapType(rs.getString(2)), null);
        });

        List<DataColumn> filtered = new ArrayList<>();
        for (DataColumn c : all) {
            String key = lower(c.key());
            if (key.equals(keyColumn) || key.equals(asOfColumn)
                    || key.equals(codeColumn) || key.equals(valueColumn)) {
                continue;
            }
            filtered.add(c);
        }
        return filtered;
    }

    /**
     * List the distinct, non-null values of a {@code KEY_VALUE} table's code column — the set of
     * measure identifiers physically present in the data — so an admin can pick a measure's
     * {@code sourceCode} from a list instead of typing it blindly.
     *
     * @throws ApiException 400 if the table is not {@code KEY_VALUE} / has no code column.
     */
    @SuppressFBWarnings(
            value = "SQL_INJECTION_SPRING_JDBC",
            justification = "The code/table/schema identifiers are validated against SqlIdentifiers' "
                    + "strict pattern before concatenation; no untrusted data is interpolated and the "
                    + "query selects a single validated column. FindSecBugs cannot prove the dynamic "
                    + "SQL is safe; the validation above guarantees it.")
    public List<String> discoverCodeValues(DataSource source, DataSourceTable table) {
        if (table.getLayout() != TableLayout.KEY_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TABLE_LAYOUT",
                    "Code discovery is only available for KEY_VALUE tables");
        }
        String codeColumn = SqlIdentifiers.require(table.getCodeColumn(), "codeColumn");
        String tableName = SqlIdentifiers.require(table.getTableName(), "tableName");
        String schema = SqlIdentifiers.optional(table.getSchemaName(), "schema");
        String qualified = schema == null ? tableName : schema + "." + tableName;

        JdbcTemplate jdbc = new JdbcTemplate(buildDataSource(source));
        String sql = "SELECT DISTINCT " + codeColumn + " FROM " + qualified
                + " WHERE " + codeColumn + " IS NOT NULL ORDER BY " + codeColumn;
        return jdbc.query(sql, (rs, rowNum) -> rs.getString(1));
    }

    private List<DataColumn> fromCatalog() {
        List<DataColumn> columns = new ArrayList<>();
        for (var c : catalogClient.fetchCatalog().safeColumns()) {
            String label = (c.label() == null || c.label().isBlank()) ? c.key() : c.label();
            columns.add(new DataColumn(c.key(), label, mapType(c.dataType()), null));
        }
        return columns;
    }

    private List<DataColumn> fromMssql(DataSource source) {
        String table = require(source, "tableName");
        String schema = source.config("schema");
        String cpIdColumn = lower(source.config("cpIdColumn"));
        String asOfColumn = lower(source.config("asOfColumn"));

        JdbcTemplate jdbc = new JdbcTemplate(buildDataSource(source));
        StringBuilder sql = new StringBuilder(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ?");
        List<Object> args = new ArrayList<>();
        args.add(table);
        if (schema != null && !schema.isBlank()) {
            sql.append(" AND table_schema = ?");
            args.add(schema);
        }
        sql.append(" ORDER BY ordinal_position");

        List<DataColumn> all = jdbc.query(sql.toString(), args.toArray(), (rs, rowNum) -> {
            String name = rs.getString(1);
            String type = rs.getString(2);
            return new DataColumn(name, name, mapType(type), null);
        });

        List<DataColumn> filtered = new ArrayList<>();
        for (DataColumn c : all) {
            String key = lower(c.key());
            if (key.equals(cpIdColumn) || key.equals(asOfColumn)) {
                continue;
            }
            filtered.add(c);
        }
        return filtered;
    }

    private javax.sql.DataSource buildDataSource(DataSource source) {
        String jdbcUrl = require(source, "jdbcUrl");
        JdbcUrlPolicy.requireSafe(jdbcUrl);
        JdbcUrlPolicy.requireAllowedHost(jdbcUrl, allowedJdbcHosts);
        DriverManagerDataSource ds = new DriverManagerDataSource(jdbcUrl);
        String username = source.config("username");
        if (username != null && !username.isBlank()) {
            ds.setUsername(username);
        }
        String password = DataSourceSecrets.password(source);
        if (password != null) {
            ds.setPassword(password);
        }
        return ds;
    }

    private static String require(DataSource source, String key) {
        String value = source.config(key);
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_SOURCE_CONFIG_MISSING",
                    "Data source '" + source.getName() + "' is missing required config: " + key);
        }
        return value;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static DataColumnType mapType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return DataColumnType.STRING;
        }
        String t = rawType.toUpperCase(Locale.ROOT).trim();
        if (t.equals("BIT") || t.contains("BOOL")) {
            return DataColumnType.BOOLEAN;
        }
        if (t.contains("CHAR") || t.contains("TEXT") || t.contains("CLOB") || t.equals("STRING")) {
            return DataColumnType.STRING;
        }
        if (t.contains("DATE") || t.contains("TIME")) {
            return DataColumnType.DATE;
        }
        if (t.contains("INT") || t.contains("DEC") || t.contains("NUM") || t.contains("FLOAT")
                || t.contains("REAL") || t.contains("DOUBLE") || t.contains("MONEY")) {
            return DataColumnType.NUMBER;
        }
        return DataColumnType.STRING;
    }
}
