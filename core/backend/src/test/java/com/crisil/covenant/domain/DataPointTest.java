package com.crisil.covenant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataPointTest {

    @Test
    void exposesDateValuesAndSafeValuesWhenPopulated() {
        Map<String, Object> values = Map.of("DSCR", 1.5, "ICR", 2.0);
        DataPoint point = new DataPoint(LocalDate.parse("2026-03-31"), values);

        assertThat(point.asOfDate()).isEqualTo(LocalDate.parse("2026-03-31"));
        assertThat(point.values()).isEqualTo(values);
        assertThat(point.safeValues()).isEqualTo(values);
    }

    @Test
    void safeValuesCoalescesNullMapToEmpty() {
        DataPoint point = new DataPoint(null, null);

        assertThat(point.asOfDate()).isNull();
        assertThat(point.values()).isNull();
        assertThat(point.safeValues()).isEmpty();
    }
}
