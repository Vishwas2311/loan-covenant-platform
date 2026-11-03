package com.crisil.covenant.formula;

import java.util.Optional;

/**
 * US-AGG-11: the closed set of sampling keywords a covenant author may write as the optional third
 * argument of an aggregation, e.g. {@code AVG(PAT, '3Q', PERIODIC)}. Each keyword maps to a
 * {@link SamplingMode} + {@link PeriodInclusion} pair and carries a UI label for the formula-builder
 * palette. This is the single source of truth bridging the surface keyword and the engine's
 * semantics (parser, canonical key, and builder metadata all go through it).
 */
public enum SamplingKeyword {

    /** Aggregate every reading in the window (default; omitted from the rendered formula). */
    FLAT(SamplingMode.FLAT, PeriodInclusion.DEFAULT, "All readings"),

    /** Period-end per period; the in-progress period follows the organisation default. */
    PERIODIC(SamplingMode.PERIODIC, PeriodInclusion.DEFAULT, "Period-end (organisation default)"),

    /** Period-end per period; always include the in-progress (as-of) period. */
    PERIODIC_CURRENT(SamplingMode.PERIODIC, PeriodInclusion.INCLUDE_CURRENT,
            "Period-end (include current period)"),

    /** Period-end per period; only completed periods (period end on/before the as-of date). */
    PERIODIC_COMPLETED(SamplingMode.PERIODIC, PeriodInclusion.COMPLETED_ONLY,
            "Period-end (completed periods only)");

    private final SamplingMode mode;
    private final PeriodInclusion inclusion;
    private final String label;

    SamplingKeyword(SamplingMode mode, PeriodInclusion inclusion, String label) {
        this.mode = mode;
        this.inclusion = inclusion;
        this.label = label;
    }

    public SamplingMode mode() {
        return mode;
    }

    public PeriodInclusion inclusion() {
        return inclusion;
    }

    public String label() {
        return label;
    }

    /** Resolve a keyword from its (case-insensitive) surface text. */
    public static Optional<SamplingKeyword> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (SamplingKeyword k : values()) {
            if (k.name().equalsIgnoreCase(name)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    /** The keyword for a (mode, inclusion) pair — the inverse of {@link #mode()}/{@link #inclusion()}. */
    public static SamplingKeyword of(SamplingMode mode, PeriodInclusion inclusion) {
        // FLAT ignores inclusion; any inclusion under FLAT still means FLAT.
        if (mode == SamplingMode.FLAT) {
            return FLAT;
        }
        return switch (inclusion) {
            case DEFAULT -> PERIODIC;
            case INCLUDE_CURRENT -> PERIODIC_CURRENT;
            case COMPLETED_ONLY -> PERIODIC_COMPLETED;
        };
    }
}
