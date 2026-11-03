package com.crisil.covenant.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DataCatalogResponse(List<Column> columns) {

    public List<Column> safeColumns() {
        return columns == null ? List.of() : columns;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Column(String key, String label, String dataType, String kind) {
    }
}
