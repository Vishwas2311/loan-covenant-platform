package com.crisil.covenant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataSeriesResponseTest {

    @Test
    void exposesEveryComponentAndSafeAccessorsWhenPopulated() {
        DataPoint point = new DataPoint(LocalDate.parse("2026-03-31"), Map.of("DSCR", 1.5));
        Instant changed = Instant.parse("2026-03-31T00:00:00Z");
        DataSeriesResponse response = new DataSeriesResponse("CP-1", List.of(point), List.of("ICR"), changed);

        assertThat(response.cpId()).isEqualTo("CP-1");
        assertThat(response.points()).containsExactly(point);
        assertThat(response.missingKeys()).containsExactly("ICR");
        assertThat(response.sourceChangedAt()).isEqualTo(changed);
        assertThat(response.safePoints()).containsExactly(point);
        assertThat(response.safeMissingKeys()).containsExactly("ICR");
    }

    @Test
    void safeAccessorsCoalesceNullCollectionsToEmpty() {
        DataSeriesResponse response = new DataSeriesResponse("CP-1", null, null, null);

        assertThat(response.points()).isNull();
        assertThat(response.missingKeys()).isNull();
        assertThat(response.sourceChangedAt()).isNull();
        assertThat(response.safePoints()).isEmpty();
        assertThat(response.safeMissingKeys()).isEmpty();
    }
}
