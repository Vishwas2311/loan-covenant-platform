package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StringMapConverterTest {

    private final StringMapConverter converter = new StringMapConverter();

    @Test
    void serialisesNullAttributeAsEmptyObject() {
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("{}");
    }

    @Test
    void serialisesPopulatedMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("jdbcUrl", "jdbc:h2:mem:x");
        assertThat(converter.convertToDatabaseColumn(map)).contains("jdbcUrl");
    }

    @Test
    void deserialisesNullToEmptyMap() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }

    @Test
    void deserialisesBlankToEmptyMap() {
        assertThat(converter.convertToEntityAttribute("   ")).isEmpty();
    }

    @Test
    void deserialisesJsonObject() {
        Map<String, String> result = converter.convertToEntityAttribute("{\"a\":\"b\"}");
        assertThat(result).containsEntry("a", "b");
    }

    @Test
    void deserialisingInvalidJsonThrows() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{not-json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deserialise");
    }
}
