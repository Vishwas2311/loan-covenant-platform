package com.crisil.covenant.formula;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * US-AGG-11: the {@link SamplingKeyword} enum bridging the surface keyword, the engine's
 * (mode, inclusion) semantics, and the builder-palette label.
 */
class SamplingKeywordTest {

    @Test
    void fromNameIsCaseInsensitiveForEveryKeyword() {
        assertThat(SamplingKeyword.fromName("flat")).contains(SamplingKeyword.FLAT);
        assertThat(SamplingKeyword.fromName("PERIODIC")).contains(SamplingKeyword.PERIODIC);
        assertThat(SamplingKeyword.fromName("Periodic_Current")).contains(SamplingKeyword.PERIODIC_CURRENT);
        assertThat(SamplingKeyword.fromName("periodic_completed")).contains(SamplingKeyword.PERIODIC_COMPLETED);
    }

    @Test
    void fromNameIsEmptyForUnknownOrNull() {
        assertThat(SamplingKeyword.fromName("weekly")).isEmpty();
        assertThat(SamplingKeyword.fromName(null)).isEmpty();
    }

    @Test
    void modeInclusionAndLabelAreExposed() {
        assertThat(SamplingKeyword.FLAT.mode()).isEqualTo(SamplingMode.FLAT);
        assertThat(SamplingKeyword.FLAT.inclusion()).isEqualTo(PeriodInclusion.DEFAULT);
        assertThat(SamplingKeyword.PERIODIC.mode()).isEqualTo(SamplingMode.PERIODIC);
        assertThat(SamplingKeyword.PERIODIC.inclusion()).isEqualTo(PeriodInclusion.DEFAULT);
        assertThat(SamplingKeyword.PERIODIC_CURRENT.inclusion()).isEqualTo(PeriodInclusion.INCLUDE_CURRENT);
        assertThat(SamplingKeyword.PERIODIC_COMPLETED.inclusion()).isEqualTo(PeriodInclusion.COMPLETED_ONLY);
        for (SamplingKeyword k : SamplingKeyword.values()) {
            assertThat(k.label()).isNotBlank();
        }
    }

    @Test
    void ofMapsModeAndInclusionBackToTheKeyword() {
        assertThat(SamplingKeyword.of(SamplingMode.FLAT, PeriodInclusion.DEFAULT))
                .isEqualTo(SamplingKeyword.FLAT);
        assertThat(SamplingKeyword.of(SamplingMode.PERIODIC, PeriodInclusion.DEFAULT))
                .isEqualTo(SamplingKeyword.PERIODIC);
        assertThat(SamplingKeyword.of(SamplingMode.PERIODIC, PeriodInclusion.INCLUDE_CURRENT))
                .isEqualTo(SamplingKeyword.PERIODIC_CURRENT);
        assertThat(SamplingKeyword.of(SamplingMode.PERIODIC, PeriodInclusion.COMPLETED_ONLY))
                .isEqualTo(SamplingKeyword.PERIODIC_COMPLETED);
    }

    @Test
    void ofTreatsAnyInclusionUnderFlatAsFlat() {
        // FLAT ignores inclusion — an author cannot select a trailing-period policy for it.
        assertThat(SamplingKeyword.of(SamplingMode.FLAT, PeriodInclusion.INCLUDE_CURRENT))
                .isEqualTo(SamplingKeyword.FLAT);
        assertThat(SamplingKeyword.of(SamplingMode.FLAT, PeriodInclusion.COMPLETED_ONLY))
                .isEqualTo(SamplingKeyword.FLAT);
    }
}
