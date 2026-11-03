package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.enums.IngestStatus;
import org.junit.jupiter.api.Test;

class IngestStatusConverterTest {

    private final IngestStatusConverter converter = new IngestStatusConverter();

    @Test
    void writesEnumAsLowerCaseName() {
        assertThat(converter.convertToDatabaseColumn(IngestStatus.OK)).isEqualTo("ok");
        assertThat(converter.convertToDatabaseColumn(IngestStatus.SKIPPED)).isEqualTo("skipped");
        assertThat(converter.convertToDatabaseColumn(IngestStatus.FAILED)).isEqualTo("failed");
    }

    @Test
    void writesNullAsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void readsLowerCaseNameBackToEnumCaseInsensitively() {
        assertThat(converter.convertToEntityAttribute("ok")).isEqualTo(IngestStatus.OK);
        assertThat(converter.convertToEntityAttribute("SKIPPED")).isEqualTo(IngestStatus.SKIPPED);
        assertThat(converter.convertToEntityAttribute("Failed")).isEqualTo(IngestStatus.FAILED);
    }

    @Test
    void readsNullAsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
