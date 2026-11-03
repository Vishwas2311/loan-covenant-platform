package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.exception.DuplicateResourceException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.dto.CreateDataSourceRequest;
import com.crisil.covenant.dto.DataSourceResponse;
import com.crisil.covenant.dto.TestConnectionResult;
import com.crisil.covenant.dto.UpdateDataSourceRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.crisil.covenant.domain.DataColumn;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.enums.DataSourceType;
import com.crisil.covenant.repository.DataSourceRepository;

@ExtendWith(MockitoExtension.class)
class DataSourceServiceTest {

    @Mock private DataSourceRepository repository;
    @Mock private ColumnDiscoveryService columnDiscovery;
    @InjectMocks private DataSourceService service;

    private DataSource existing(String name) {
        DataSource ds = new DataSource();
        ds.setName(name);
        ds.setType(DataSourceType.MSSQL);
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("jdbcUrl", "jdbc:h2:mem:x");
        cfg.put("password", "stored-secret");
        ds.setConnectionConfig(cfg);
        return ds;
    }

    private void echoSave() {
        when(repository.saveAndFlush(any(DataSource.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createPersistsAndReturnsResponse() {
        when(repository.existsByNameIgnoreCase("REST")).thenReturn(false);
        echoSave();
        CreateDataSourceRequest req =
                new CreateDataSourceRequest("REST", DataSourceType.REST, "d", Map.of("valuesPath", "/v"));

        DataSourceResponse r = service.create(req);
        assertThat(r.name()).isEqualTo("REST");
        assertThat(r.type()).isEqualTo(DataSourceType.REST);
        assertThat(r.active()).isTrue();
        assertThat(r.connectionConfig()).containsEntry("valuesPath", "/v");
    }

    @Test
    void createRejectsDuplicateName() {
        when(repository.existsByNameIgnoreCase("REST")).thenReturn(true);
        CreateDataSourceRequest req =
                new CreateDataSourceRequest("REST", DataSourceType.REST, null, null);
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void updateChangesFields() {
        DataSource ds = existing("Old");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        echoSave();
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("jdbcUrl", "jdbc:h2:mem:new");
        UpdateDataSourceRequest req =
                new UpdateDataSourceRequest("New", DataSourceType.MSSQL, "desc2", cfg);

        DataSourceResponse r = service.update("1", req);
        assertThat(r.name()).isEqualTo("New");
        assertThat(r.description()).isEqualTo("desc2");
    }

    @Test
    void updateSameNameDoesNotCheckDuplicate() {
        DataSource ds = existing("Same");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        echoSave();
        UpdateDataSourceRequest req =
                new UpdateDataSourceRequest("same", DataSourceType.MSSQL, null,
                        Map.of("jdbcUrl", "jdbc:sqlserver://db:1433"));
        DataSourceResponse r = service.update("1", req);
        assertThat(r.name()).isEqualTo("same");
    }

    @Test
    void updateRejectsRenameToExistingName() {
        DataSource ds = existing("Old");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        when(repository.existsByNameIgnoreCase("Taken")).thenReturn(true);
        UpdateDataSourceRequest req =
                new UpdateDataSourceRequest("Taken", DataSourceType.MSSQL, null, null);
        assertThatThrownBy(() -> service.update("1", req))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void updateKeepsStoredPasswordWhenRedactedPlaceholderSent() {
        DataSource ds = existing("Old");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        echoSave();
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("jdbcUrl", "jdbc:h2:mem:x");
        cfg.put("password", "********");
        UpdateDataSourceRequest req =
                new UpdateDataSourceRequest("Old", DataSourceType.MSSQL, null, cfg);

        service.update("1", req);
        // The stored secret is preserved internally; the response redacts it again.
        assertThat(ds.config("password")).isEqualTo("stored-secret");
    }

    @Test
    void updateRemovesPasswordWhenPlaceholderSentButNoneStored() {
        DataSource ds = existing("Old");
        ds.getConnectionConfig().remove("password");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        echoSave();
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("jdbcUrl", "jdbc:h2:mem:x");
        cfg.put("password", "********");
        UpdateDataSourceRequest req =
                new UpdateDataSourceRequest("Old", DataSourceType.MSSQL, null, cfg);

        service.update("1", req);
        assertThat(ds.config("password")).isNull();
    }

    @Test
    void updateWithSecretRefDropsTheStoredPlaintextPassword() {
        DataSource ds = existing("Old");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        echoSave();
        UpdateDataSourceRequest req = new UpdateDataSourceRequest("Old", DataSourceType.MSSQL, null,
                Map.of("jdbcUrl", "jdbc:h2:mem:x", "password", "********", "secretRef", "COVENANT_DS_FIN"));

        service.update("1", req);
        assertThat(ds.config("secretRef")).isEqualTo("COVENANT_DS_FIN");
        assertThat(ds.config("password")).isNull();
    }

    @Test
    void createRejectsSecretRefOutsideTheAllowedPrefix() {
        when(repository.existsByNameIgnoreCase("S")).thenReturn(false);
        CreateDataSourceRequest req = new CreateDataSourceRequest("S", DataSourceType.MSSQL, null,
                Map.of("jdbcUrl", "jdbc:h2:mem:x", "secretRef", "MSSQL_SA_PASSWORD"));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("DATA_SOURCE_SECRET_REF_INVALID");
    }

    @Test
    void plaintextPasswordRefusedWhenPolicyIsOff() {
        ReflectionTestUtils.setField(service, "allowPlaintextPassword", false);
        when(repository.existsByNameIgnoreCase("S")).thenReturn(false);
        CreateDataSourceRequest req = new CreateDataSourceRequest("S", DataSourceType.MSSQL, null,
                Map.of("jdbcUrl", "jdbc:h2:mem:x", "password", "plain"));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("DATA_SOURCE_PLAINTEXT_PASSWORD");
    }

    @Test
    void jdbcHostCheckedOnSave() {
        ReflectionTestUtils.setField(service, "allowedJdbcHosts", List.of("db.bank.internal"));
        when(repository.existsByNameIgnoreCase(any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(new CreateDataSourceRequest("X", DataSourceType.MSSQL, null,
                Map.of("jdbcUrl", "jdbc:sqlserver://attacker.example:1433"))))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("DATA_SOURCE_HOST_NOT_ALLOWED");
    }

    @Test
    void jdbcUrlCheckedOnSave() {
        ReflectionTestUtils.setField(service, "allowH2", false);
        when(repository.existsByNameIgnoreCase(any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(new CreateDataSourceRequest("H", DataSourceType.MSSQL, null,
                Map.of("jdbcUrl", "jdbc:h2:mem:x"))))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("DATA_SOURCE_JDBC_URL_INVALID");
        assertThatThrownBy(() -> service.create(new CreateDataSourceRequest("P", DataSourceType.MSSQL, null,
                Map.of("jdbcUrl", "jdbc:postgresql://db/x"))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void emptyPasswordOrSecretRefAllowedWhenPolicyIsOff() {
        ReflectionTestUtils.setField(service, "allowPlaintextPassword", false);
        when(repository.existsByNameIgnoreCase(any())).thenReturn(false);
        echoSave();

        DataSourceResponse empty = service.create(new CreateDataSourceRequest("A", DataSourceType.MSSQL,
                null, Map.of("jdbcUrl", "jdbc:h2:mem:x", "password", "")));
        DataSourceResponse ref = service.create(new CreateDataSourceRequest("B", DataSourceType.MSSQL,
                null, Map.of("jdbcUrl", "jdbc:h2:mem:x", "secretRef", "COVENANT_DS_B")));
        assertThat(empty.connectionConfig()).containsEntry("jdbcUrl", "jdbc:h2:mem:x");
        assertThat(ref.connectionConfig()).containsEntry("secretRef", "COVENANT_DS_B");
    }

    @Test
    void setActiveTogglesFlag() {
        DataSource ds = existing("Old");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        echoSave();
        DataSourceResponse r = service.setActive("1", false);
        assertThat(r.active()).isFalse();
    }

    @Test
    void getReturnsResponse() {
        DataSource ds = existing("Old");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        assertThat(service.get("1").name()).isEqualTo("Old");
    }

    @Test
    void getMissingThrows() {
        when(repository.findById("9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("9")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listActiveOnlyUsesActiveQuery() {
        when(repository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(existing("A")));
        assertThat(service.list(true)).hasSize(1);
    }

    @Test
    void listAllUsesFullQueryForFalseAndNull() {
        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(existing("A"), existing("B")));
        assertThat(service.list(false)).hasSize(2);
        assertThat(service.list(null)).hasSize(2);
    }

    @Test
    void columnsDelegatesToDiscovery() {
        DataSource ds = existing("Old");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        when(columnDiscovery.discover(ds))
                .thenReturn(List.of(DataColumn.of("DSCR", "DSCR", DataColumnType.NUMBER)));
        assertThat(service.columns("1")).hasSize(1);
    }

    @Test
    void testConnectionOkReportsColumnCount() {
        DataSource ds = existing("Old");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        when(columnDiscovery.discover(ds)).thenReturn(List.of(
                DataColumn.of("a", "a", DataColumnType.NUMBER),
                DataColumn.of("b", "b", DataColumnType.STRING)));
        TestConnectionResult r = service.testConnection("1");
        assertThat(r.reachable()).isTrue();
        assertThat(r.columnCount()).isEqualTo(2);
    }

    @Test
    void testConnectionFailedUsesExceptionMessage() {
        DataSource ds = existing("Old");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        when(columnDiscovery.discover(ds)).thenThrow(new RuntimeException("unreachable"));
        TestConnectionResult r = service.testConnection("1");
        assertThat(r.reachable()).isFalse();
        assertThat(r.message()).isEqualTo("unreachable");
    }

    @Test
    void testConnectionFailedFallsBackToClassNameWhenMessageNull() {
        DataSource ds = existing("Old");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        when(columnDiscovery.discover(ds)).thenThrow(new RuntimeException((String) null));
        TestConnectionResult r = service.testConnection("1");
        assertThat(r.reachable()).isFalse();
        assertThat(r.message()).isEqualTo("RuntimeException");
    }

    @Test
    void requireEntityReturnsEntityOrThrows() {
        DataSource ds = existing("Old");
        when(repository.findById("1")).thenReturn(Optional.of(ds));
        assertThat(service.requireEntity("1")).isSameAs(ds);

        when(repository.findById("2")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireEntity("2"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
