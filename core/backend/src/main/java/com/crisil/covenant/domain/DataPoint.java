package com.crisil.covenant.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.Map;

/**
 * One dated observation in a {@link DataSeriesResponse}: the measure values published for an entity
 * on a single as-of date. Unknown JSON fields are ignored so the upstream contract can grow without
 * breaking deserialisation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DataPoint(LocalDate asOfDate, Map<String, Object> values) {

    public Map<String, Object> safeValues() {
        return values == null ? Map.of() : values;
    }
}
