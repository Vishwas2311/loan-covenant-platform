package com.crisil.covenant.formula;

import java.time.LocalDate;
import java.util.Optional;

/**
 * A time window such as "2 years" ({@code 2y}) or "6 months" ({@code 6m}) used as the second
 * argument of an aggregation. The amount is bounded to {@code [1, 1000]}.
 */
public record Window(int amount, WindowUnit unit) {

    public static final int MIN_AMOUNT = 1;
    public static final int MAX_AMOUNT = 1000;

    public Window {
        if (amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("Window amount must be between 1 and 1000");
        }
    }

    /** The canonical text form, e.g. {@code 2y}. */
    public String canonical() {
        return amount + String.valueOf(unit.symbol());
    }

    /** The inclusive lower bound of this window relative to {@code asOf}. */
    public LocalDate startFrom(LocalDate asOf) {
        return unit.subtract(asOf, amount);
    }

    /**
     * Parse a window spec such as {@code "2y"} / {@code "90d"}. Returns empty when the spec is
     * malformed or the amount is out of range — callers turn that into an INVALID_WINDOW error.
     */
    public static Optional<Window> parse(String spec) {
        if (spec == null) {
            return Optional.empty();
        }
        String s = spec.trim();
        if (s.length() < 2) {
            return Optional.empty();
        }
        Optional<WindowUnit> unit = WindowUnit.fromSymbol(s.charAt(s.length() - 1));
        if (unit.isEmpty()) {
            return Optional.empty();
        }
        String digits = s.substring(0, s.length() - 1);
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        try {
            int amount = Integer.parseInt(digits);
            if (amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
                return Optional.empty();
            }
            return Optional.of(new Window(amount, unit.get()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
