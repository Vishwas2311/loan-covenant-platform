package com.crisil.covenant.entity;

import com.crisil.covenant.entity.AuditableEntity;
import com.crisil.covenant.util.StringMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import java.util.LinkedHashMap;
import java.util.Map;
import com.crisil.covenant.enums.DataSourceType;

/**
 * A reusable, named data source. Each is either a {@link DataSourceType#REST} endpoint or an
 * {@link DataSourceType#MSSQL} table — both CP_ID-keyed so values can be fetched and evaluated
 * per credit proposal. Type-specific connection details live in {@link #connectionConfig}.
 *
 * <p>REST config keys: {@code catalogPath}, {@code valuesPath}.
 * <p>MSSQL config keys: {@code jdbcUrl}, {@code username}, {@code password} or {@code secretRef},
 * {@code schema} (optional), {@code tableName}, {@code cpIdColumn}, {@code asOfColumn} (optional).
 * Credentials are stored as a reference where possible — never a plaintext password in production.
 */
@Entity
@Table(name = "cov_data_source")
public class DataSource extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "type", nullable = false, length = 16)
    private DataSourceType type;

    @Column(name = "description", length = 2000)
    private String description;

    @Convert(converter = StringMapConverter.class)
    @Column(name = "connection_config_json", nullable = false, length = 2000)
    private Map<String, String> connectionConfig = new LinkedHashMap<>();

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DataSourceType getType() {
        return type;
    }

    public void setType(DataSourceType type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, String> getConnectionConfig() {
        return connectionConfig;
    }

    public void setConnectionConfig(Map<String, String> connectionConfig) {
        this.connectionConfig = connectionConfig == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(connectionConfig);
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /** Convenience accessor for a single connection-config value. */
    public String config(String key) {
        return connectionConfig == null ? null : connectionConfig.get(key);
    }

    /** Connection-config value with a fallback when missing or blank. */
    public String configOrDefault(String key, String fallback) {
        String v = config(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
