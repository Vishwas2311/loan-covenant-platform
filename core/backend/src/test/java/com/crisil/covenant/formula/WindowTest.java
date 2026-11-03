package com.crisil.covenant.formula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WindowTest {

    @Test
    void parsesEachUnit() {
        assertThat(Window.parse("2y")).contains(new Window(2, WindowUnit.Y));
        assertThat(Window.parse("6m")).contains(new Window(6, WindowUnit.M));
        assertThat(Window.parse("90d")).contains(new Window(90, WindowUnit.D));
        assertThat(Window.parse("1q")).contains(new Window(1, WindowUnit.Q));
        assertThat(Window.parse("4w")).contains(new Window(4, WindowUnit.W));
    }

    @Test
    void parseIsCaseInsensitiveAndTrims() {
        assertThat(Window.parse("2Y")).contains(new Window(2, WindowUnit.Y));
        assertThat(Window.parse("  3m  ")).contains(new Window(3, WindowUnit.M));
    }

    @Test
    void rejectsMalformedSpecs() {
        assertThat(Window.parse(null)).isEmpty();
        assertThat(Window.parse("")).isEmpty();
        assertThat(Window.parse("y")).isEmpty();
        assertThat(Window.parse("2")).isEmpty();
        assertThat(Window.parse("2z")).isEmpty();
        assertThat(Window.parse("0y")).isEmpty();
        assertThat(Window.parse("1001y")).isEmpty();
        assertThat(Window.parse("xy")).isEmpty();
        assertThat(Window.parse("999999999999y")).isEmpty(); // numeric overflow
    }

    @Test
    void canonicalRoundTrips() {
        assertThat(new Window(2, WindowUnit.Y).canonical()).isEqualTo("2y");
        assertThat(new Window(6, WindowUnit.M).canonical()).isEqualTo("6m");
    }

    @Test
    void constructorRejectsOutOfRange() {
        assertThatThrownBy(() -> new Window(0, WindowUnit.Y)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Window(1001, WindowUnit.Y)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startFromSubtractsWindow() {
        LocalDate asOf = LocalDate.of(2026, 6, 19);
        assertThat(new Window(2, WindowUnit.Y).startFrom(asOf)).isEqualTo(LocalDate.of(2024, 6, 19));
        assertThat(new Window(1, WindowUnit.Q).startFrom(asOf)).isEqualTo(LocalDate.of(2026, 3, 19));
    }
}
