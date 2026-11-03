package com.crisil.covenant.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists a {@code Map<String,String>} as a JSON object string. Used for the flexible,
 * type-specific connection configuration on {@code data_source}.
 */
@Converter
public class StringMapConverter implements AttributeConverter<Map<String, String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        try {
            return MAPPER.writeValueAsString(attribute == null ? Collections.emptyMap() : attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise string map", e);
        }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialise string map: " + dbData, e);
        }
    }
}
