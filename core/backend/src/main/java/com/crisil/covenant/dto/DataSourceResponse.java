package com.crisil.covenant.dto;

import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.enums.DataSourceType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record DataSourceResponse(
        String id,
        String name,
        DataSourceType type,
        String description,
        Map<String, String> connectionConfig,
        boolean active,
        Instant createdDate,
        String createdBy,
        Instant modifiedDate,
        String modifiedBy,
        String version) {

    public static DataSourceResponse from(DataSource entity) {
        return new DataSourceResponse(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getDescription(),
                redact(entity.getConnectionConfig()),
                entity.isActive(),
                entity.getCreatedDate(),
                entity.getCreatedBy(),
                entity.getModifiedDate(),
                entity.getModifiedBy(),
                entity.getVersion());
    }

    /** Never echo a plaintext password back to clients. */
    private static Map<String, String> redact(Map<String, String> config) {
        Map<String, String> copy = new LinkedHashMap<>(config == null ? Map.of() : config);
        if (copy.containsKey("password")) {
            copy.put("password", "********");
        }
        return copy;
    }
}
