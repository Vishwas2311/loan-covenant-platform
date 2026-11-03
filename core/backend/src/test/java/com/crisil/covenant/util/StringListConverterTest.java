package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StringListConverterTest {

    private final StringListConverter converter = new StringListConverter();

    @Test
    void writesListAsJsonArray() {
        String json = converter.convertToDatabaseColumn(List.of("DSCR", "CAR"));
        assertThat(json).isEqualTo("[\"DSCR\",\"CAR\"]");
    }

    @Test
    void writesNullAsEmptyArray() {
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("[]");
    }

    @Test
    void readsJsonArrayAsList() {
        List<String> parsed = converter.convertToEntityAttribute("[\"DSCR\",\"CAR\"]");
        assertThat(parsed).containsExactly("DSCR", "CAR");
    }

    @Test
    void readsNullOrBlankAsEmptyList() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
        assertThat(converter.convertToEntityAttribute("  ")).isEmpty();
    }

    @Test
    void invalidJsonThrowsIllegalState() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("not-json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to deserialise");
    }
}
