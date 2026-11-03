package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.covenant.config.IngestionProperties;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.enums.DataSourceType;
import com.crisil.covenant.exception.ApiException;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies the per-source pooling contract: one live pool per source id, an atomic rebuild-and-close
 * when a source's connection config changes, and shutdown cleanup. Uses in-memory H2 URLs so a real
 * Hikari pool actually starts and connects.
 */
class JdbcDataSourceRegistryTest {

    private final JdbcDataSourceRegistry registry =
            new JdbcDataSourceRegistry(new IngestionProperties());

    @AfterEach
    void tearDown() {
        registry.closeAll();
    }

    @Test
    void returnsSamePooledInstanceForUnchangedSource() {
        DataSource source = mssql("src-1", "jdbc:h2:mem:reg_same;DB_CLOSE_DELAY=-1");

        javax.sql.DataSource first = registry.dataSourceFor(source);
        javax.sql.DataSource second = registry.dataSourceFor(source);

        assertThat(first).isSameAs(second); // second call reuses the live pool, no rebuild
        assertThat(first).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) first).isClosed()).isFalse();
    }

    @Test
    void rebuildsAndClosesPriorPoolWhenConfigChanges() {
        DataSource source = mssql("src-1", "jdbc:h2:mem:reg_before;DB_CLOSE_DELAY=-1");
        HikariDataSource original = (HikariDataSource) registry.dataSourceFor(source);

        // Same source id, new URL → the signature changes, so the pool is rebuilt and the old closed.
        source.setConnectionConfig(Map.of("jdbcUrl", "jdbc:h2:mem:reg_after;DB_CLOSE_DELAY=-1"));
        HikariDataSource rebuilt = (HikariDataSource) registry.dataSourceFor(source);

        assertThat(rebuilt).isNotSameAs(original);
        assertThat(original.isClosed()).isTrue();
        assertThat(rebuilt.isClosed()).isFalse();
    }

    @Test
    void closeAllClosesEveryPool() {
        HikariDataSource a = (HikariDataSource) registry.dataSourceFor(
                mssql("src-a", "jdbc:h2:mem:reg_a;DB_CLOSE_DELAY=-1"));
        HikariDataSource b = (HikariDataSource) registry.dataSourceFor(
                mssql("src-b", "jdbc:h2:mem:reg_b;DB_CLOSE_DELAY=-1"));

        registry.closeAll();

        assertThat(a.isClosed()).isTrue();
        assertThat(b.isClosed()).isTrue();
    }

    @Test
    void rejectsSourceWithoutJdbcUrl() {
        DataSource source = new DataSource();
        ReflectionTestUtils.setField(source, "id", "src-blank");
        source.setName("No URL");
        source.setType(DataSourceType.MSSQL);
        source.setConnectionConfig(Map.of()); // no jdbcUrl → JdbcDataSourceFactory guard fires

        assertThatThrownBy(() -> registry.dataSourceFor(source))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("jdbcUrl");
    }

    private static DataSource mssql(String id, String jdbcUrl) {
        DataSource ds = new DataSource();
        ReflectionTestUtils.setField(ds, "id", id);
        ds.setName("MSSQL " + id);
        ds.setType(DataSourceType.MSSQL);
        ds.setConnectionConfig(Map.of("jdbcUrl", jdbcUrl));
        return ds;
    }
}
