package com.crisil.covenant.formula;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WindowUnitTest {

    @Test
    void fromSymbolResolvesEachUnitCaseInsensitively() {
        assertThat(WindowUnit.fromSymbol('d')).contains(WindowUnit.D);
        assertThat(WindowUnit.fromSymbol('w')).contains(WindowUnit.W);
        assertThat(WindowUnit.fromSymbol('m')).contains(WindowUnit.M);
        assertThat(WindowUnit.fromSymbol('q')).contains(WindowUnit.Q);
        assertThat(WindowUnit.fromSymbol('Y')).contains(WindowUnit.Y);
    }

    @Test
    void fromSymbolEmptyForUnknown() {
        assertThat(WindowUnit.fromSymbol('x')).isEmpty();
    }

    @Test
    void subtractAppliesCalendarMath() {
        LocalDate asOf = LocalDate.of(2026, 6, 19);
        assertThat(WindowUnit.D.subtract(asOf, 10)).isEqualTo(LocalDate.of(2026, 6, 9));
        assertThat(WindowUnit.W.subtract(asOf, 1)).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(WindowUnit.M.subtract(asOf, 2)).isEqualTo(LocalDate.of(2026, 4, 19));
        assertThat(WindowUnit.Q.subtract(asOf, 2)).isEqualTo(LocalDate.of(2025, 12, 19));
        assertThat(WindowUnit.Y.subtract(asOf, 5)).isEqualTo(LocalDate.of(2021, 6, 19));
    }

    @Test
    void symbolIsLowerCaseCanonical() {
        assertThat(WindowUnit.Y.symbol()).isEqualTo('y');
    }
}
