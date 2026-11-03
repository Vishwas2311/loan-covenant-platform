package com.crisil.covenant.service;

import com.crisil.covenant.exception.DuplicateResourceException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.dto.CreateDataSourceRequest;
import com.crisil.covenant.dto.DataSourceResponse;
import com.crisil.covenant.dto.TestConnectionResult;
import com.crisil.covenant.dto.UpdateDataSourceRequest;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.util.DataSourceSecrets;
import com.crisil.covenant.util.JdbcUrlPolicy;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crisil.covenant.domain.DataColumn;
import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.repository.DataSourceRepository;

@Service
@Transactional
public class DataSourceService {

    private final DataSourceRepository repository;
    private final ColumnDiscoveryService columnDiscovery;

    /** Off in production: MSSQL credentials must then come from a {@code secretRef}. */
    @Value("${covenant.data-sources.allow-plaintext-password:true}")
    private boolean allowPlaintextPassword = true;

    /** Off in production and the local stack: only SQL Server JDBC URLs may be saved. */
    @Value("${covenant.data-sources.allow-h2:true}")
    private boolean allowH2 = true;

    /** SQL Server hosts data sources may name; {@code *} (the default outside prod) allows any. */
    @Value("${covenant.data-sources.allowed-jdbc-hosts:*}")
    private java.util.List<String> allowedJdbcHosts = java.util.List.of("*");

    public DataSourceService(DataSourceRepository repository, ColumnDiscoveryService columnDiscovery) {
        this.repository = repository;
        this.columnDiscovery = columnDiscovery;
    }

    public DataSourceResponse create(CreateDataSourceRequest request) {
        if (repository.existsByNameIgnoreCase(request.name())) {
            throw new DuplicateResourceException(
                    "Data source with name '" + request.name() + "' already exists");
        }
        DataSource entity = new DataSource();
        entity.setName(request.name());
        entity.setType(request.type());
        entity.setDescription(request.description());
        entity.setConnectionConfig(checkCredentials(request.safeConnectionConfig()));
        entity.setActive(true);
        return DataSourceResponse.from(repository.saveAndFlush(entity));
    }

    public DataSourceResponse update(String id, UpdateDataSourceRequest request) {
        DataSource entity = findOrThrow(id);
        if (!entity.getName().equalsIgnoreCase(request.name())
                && repository.existsByNameIgnoreCase(request.name())) {
            throw new DuplicateResourceException(
                    "Data source with name '" + request.name() + "' already exists");
        }
        entity.setName(request.name());
        entity.setType(request.type());
        entity.setDescription(request.description());
        entity.setConnectionConfig(checkCredentials(mergeSecrets(entity, request.safeConnectionConfig())));
        return DataSourceResponse.from(repository.saveAndFlush(entity));
    }

    /** If the client sent back the redacted password placeholder, keep the stored secret. */
    private static java.util.Map<String, String> mergeSecrets(
            DataSource entity, java.util.Map<String, String> request) {
        java.util.Map<String, String> incoming = new java.util.LinkedHashMap<>(request);
        if ("********".equals(incoming.get("password"))) {
            String stored = entity.config("password");
            if (stored != null) {
                incoming.put("password", stored);
            } else {
                incoming.remove("password");
            }
        }
        return incoming;
    }

    /**
     * A {@code secretRef} replaces any stored password. A non-empty plaintext password is refused when
     * {@code covenant.data-sources.allow-plaintext-password} is off.
     */
    private java.util.Map<String, String> checkCredentials(java.util.Map<String, String> incoming) {
        java.util.Map<String, String> config = new java.util.LinkedHashMap<>(incoming);
        String jdbcUrl = config.get("jdbcUrl");
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            JdbcUrlPolicy.requireAllowed(jdbcUrl, allowH2);
            JdbcUrlPolicy.requireAllowedHost(jdbcUrl, allowedJdbcHosts);
        }
        String ref = config.get("secretRef");
        if (ref != null && !ref.isBlank()) {
            DataSourceSecrets.validateRef(ref);
            config.remove("password");
            return config;
        }
        String password = config.get("password");
        if (!allowPlaintextPassword && password != null && !password.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_SOURCE_PLAINTEXT_PASSWORD",
                    "Store the password in the secret store and set secretRef (an environment variable "
                            + "starting with " + DataSourceSecrets.PREFIX + ") instead of password");
        }
        return config;
    }

    public DataSourceResponse setActive(String id, boolean active) {
        DataSource entity = findOrThrow(id);
        entity.setActive(active);
        return DataSourceResponse.from(repository.saveAndFlush(entity));
    }

    @Transactional(readOnly = true)
    public DataSourceResponse get(String id) {
        return DataSourceResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<DataSourceResponse> list(Boolean activeOnly) {
        List<DataSource> sources = Boolean.TRUE.equals(activeOnly)
                ? repository.findByActiveTrueOrderByNameAsc()
                : repository.findAllByOrderByNameAsc();
        return sources.stream().map(DataSourceResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<DataColumn> columns(String id) {
        return columnDiscovery.discover(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public TestConnectionResult testConnection(String id) {
        DataSource entity = findOrThrow(id);
        try {
            List<DataColumn> columns = columnDiscovery.discover(entity);
            return TestConnectionResult.ok(columns.size());
        } catch (RuntimeException ex) {
            return TestConnectionResult.failed(
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    /** Entity lookup for other services (e.g. assessment value resolution). */
    @Transactional(readOnly = true)
    public DataSource requireEntity(String id) {
        return findOrThrow(id);
    }

    private DataSource findOrThrow(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Data source " + id + " not found"));
    }
}
