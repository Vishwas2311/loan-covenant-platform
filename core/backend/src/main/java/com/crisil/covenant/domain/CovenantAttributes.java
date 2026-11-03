package com.crisil.covenant.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CovenantAttributes(
        String cpId,
        String businessSegment,
        String sector,
        Map<String, String> attributes) {

    public Map<String, String> safeAttributes() {
        return attributes == null ? Map.of() : attributes;
    }
}
