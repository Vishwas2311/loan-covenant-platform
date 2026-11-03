package com.crisil.covenant.dto;

import com.crisil.covenant.enums.DataSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

public record CreateDataSourceRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull DataSourceType type,
        @Size(max = 2000) String description,
        Map<String, String> connectionConfig) {

    public Map<String, String> safeConnectionConfig() {
        return connectionConfig == null ? new LinkedHashMap<>() : connectionConfig;
    }
}
