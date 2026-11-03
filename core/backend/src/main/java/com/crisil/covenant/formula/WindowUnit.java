package com.crisil.covenant.formula;

import java.time.LocalDate;
import java.util.Optional;

/**
 * A time-window unit used in aggregation windows such as {@code '2y'} or {@code '6m'}.
 * The single-character symbol is the canonical, lower-case form.
 */
public enum WindowUnit {
    D('d'),
    W('w'),
    M('m'),
    Q('q'),
    Y('y');

    private final char symbol;

    WindowUnit(char symbol) {
        this.symbol = symbol;
    }

    public char symbol() {
        return symbol;
    }

    /**
     * Whether this unit maps to a reporting period ({@code PERIODIC} sampling collapses each period
     * to its period-end figure). Months, quarters and years are period-capable; days and weeks are
     * not (US-AGG-11 AC7).
     */
    public boolean isPeriodCapable() {
        return this == M || this == Q || this == Y;
    }

    /** Resolve a unit from its (case-insensitive) symbol. */
    public static Optional<WindowUnit> fromSymbol(char c) {
        char lower = Character.toLowerCase(c);
        for (WindowUnit u : values()) {
            if (u.symbol == lower) {
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }

    /** The earliest date of a window of {@code amount} of this unit ending at {@code asOf}. */
    public LocalDate subtract(LocalDate asOf, int amount) {
        return switch (this) {
            case D -> asOf.minusDays(amount);
            case W -> asOf.minusWeeks(amount);
            case M -> asOf.minusMonths(amount);
            case Q -> asOf.minusMonths((long) amount * 3);
            case Y -> asOf.minusYears(amount);
        };
    }
}
