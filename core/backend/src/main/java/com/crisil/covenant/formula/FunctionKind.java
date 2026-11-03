package com.crisil.covenant.formula;

import java.util.Optional;

/**
 * The aggregation functions a formula may apply to a measure over a {@link Window}.
 *
 * <ul>
 *   <li>{@code AVG/SUM/MIN/MAX} require a numeric measure and return NUMBER.</li>
 *   <li>{@code COUNT} returns NUMBER (the number of observations) for any measure.</li>
 *   <li>{@code LATEST}/{@code CURRENT} (synonyms) return the most recent value within the window,
 *       preserving the measure's own type.</li>
 * </ul>
 */
public enum FunctionKind {
    AVG,
    SUM,
    MIN,
    MAX,
    LATEST,
    CURRENT,
    COUNT;

    /** Whether the inner measure must be numeric. */
    public boolean requiresNumericInner() {
        return switch (this) {
            case AVG, SUM, MIN, MAX -> true;
            case LATEST, CURRENT, COUNT -> false;
        };
    }

    /** The result type given the inner measure's type. */
    public FormulaType resultType(FormulaType innerType) {
        return switch (this) {
            case AVG, SUM, MIN, MAX, COUNT -> FormulaType.NUMBER;
            case LATEST, CURRENT -> innerType;
        };
    }

    /** Resolve a function from its (case-insensitive) name. */
    public static Optional<FunctionKind> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (FunctionKind k : values()) {
            if (k.name().equalsIgnoreCase(name)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }
}
