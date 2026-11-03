package com.crisil.covenant.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.dto.CreateDataSourceRequest;
import com.crisil.covenant.dto.DataSourceResponse;
import com.crisil.covenant.dto.TestConnectionResult;
import com.crisil.covenant.dto.UpdateDataSourceRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.crisil.covenant.domain.DataColumn;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.DataSourceType;

/** Plain unit coverage for the data-source entity, value objects and DTOs. */
class DataSourceUnitTest {

    @Test
    void entityGettersAndConfigHelpers() {
        DataSource ds = new DataSource();
        ds.setName("REST source");
        ds.setType(DataSourceType.REST);
        ds.setDescription("desc");
        ds.setActive(false);
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("valuesPath", "/api/data/values");
        ds.setConnectionConfig(cfg);

        assertThat(ds.getId()).isNull();
        assertThat(ds.getName()).isEqualTo("REST source");
        assertThat(ds.getType()).isEqualTo(DataSourceType.REST);
        assertThat(ds.getDescription()).isEqualTo("desc");
        assertThat(ds.isActive()).isFalse();
        assertThat(ds.getConnectionConfig()).containsEntry("valuesPath", "/api/data/values");
        assertThat(ds.config("valuesPath")).isEqualTo("/api/data/values");
        assertThat(ds.config("missing")).isNull();
        assertThat(ds.configOrDefault("missing", "fallback")).isEqualTo("fallback");
        assertThat(ds.configOrDefault("valuesPath", "fallback")).isEqualTo("/api/data/values");
    }

    @Test
    void setNullConnectionConfigYieldsEmptyMap() {
        DataSource ds = new DataSource();
        ds.setConnectionConfig(null);
        assertThat(ds.getConnectionConfig()).isEmpty();
        assertThat(ds.config("anything")).isNull();
        assertThat(ds.configOrDefault("anything", "x")).isEqualTo("x");
    }

    @Test
    void setConnectionConfigDefensivelyCopies() {
        DataSource ds = new DataSource();
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("k", "v");
        ds.setConnectionConfig(cfg);
        cfg.put("k", "mutated");
        assertThat(ds.config("k")).isEqualTo("v");
    }

    @Test
    void dataColumnFactoryAndAccessors() {
        DataColumn c = DataColumn.of("DSCR", "Debt Service Coverage", DataColumnType.NUMBER);
        assertThat(c.key()).isEqualTo("DSCR");
        assertThat(c.label()).isEqualTo("Debt Service Coverage");
        assertThat(c.dataType()).isEqualTo(DataColumnType.NUMBER);
        assertThat(c.description()).isNull();

        DataColumn full = new DataColumn("k", "l", DataColumnType.DATE, "d");
        assertThat(full.description()).isEqualTo("d");
    }

    @Test
    void createRequestSafeConnectionConfig() {
        CreateDataSourceRequest withNull =
                new CreateDataSourceRequest("n", DataSourceType.REST, "d", null);
        assertThat(withNull.safeConnectionConfig()).isEmpty();

        CreateDataSourceRequest withMap =
                new CreateDataSourceRequest("n", DataSourceType.REST, "d", Map.of("a", "b"));
        assertThat(withMap.safeConnectionConfig()).containsEntry("a", "b");
    }

    @Test
    void updateRequestSafeConnectionConfig() {
        UpdateDataSourceRequest withNull =
                new UpdateDataSourceRequest("n", DataSourceType.MSSQL, null, null);
        assertThat(withNull.safeConnectionConfig()).isEmpty();

        UpdateDataSourceRequest withMap =
                new UpdateDataSourceRequest("n", DataSourceType.MSSQL, null, Map.of("a", "b"));
        assertThat(withMap.safeConnectionConfig()).containsEntry("a", "b");
    }

    @Test
    void responseRedactsPassword() {
        DataSource ds = new DataSource();
        ds.setName("MSSQL");
        ds.setType(DataSourceType.MSSQL);
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("jdbcUrl", "jdbc:h2:mem:x");
        cfg.put("password", "secret");
        ds.setConnectionConfig(cfg);

        DataSourceResponse r = DataSourceResponse.from(ds);
        assertThat(r.name()).isEqualTo("MSSQL");
        assertThat(r.type()).isEqualTo(DataSourceType.MSSQL);
        assertThat(r.connectionConfig()).containsEntry("jdbcUrl", "jdbc:h2:mem:x");
        assertThat(r.connectionConfig()).containsEntry("password", "********");
    }

    @Test
    void responseWithoutPasswordIsUnchanged() {
        DataSource ds = new DataSource();
        ds.setName("REST");
        ds.setType(DataSourceType.REST);
        ds.setConnectionConfig(Map.of("valuesPath", "/v"));
        DataSourceResponse r = DataSourceResponse.from(ds);
        assertThat(r.connectionConfig()).doesNotContainKey("password");
        assertThat(r.active()).isTrue();
    }

    @Test
    void testConnectionResultFactories() {
        TestConnectionResult ok = TestConnectionResult.ok(5);
        assertThat(ok.reachable()).isTrue();
        assertThat(ok.columnCount()).isEqualTo(5);
        assertThat(ok.message()).contains("5");

        TestConnectionResult failed = TestConnectionResult.failed("boom");
        assertThat(failed.reachable()).isFalse();
        assertThat(failed.columnCount()).isZero();
        assertThat(failed.message()).isEqualTo("boom");
    }
}
