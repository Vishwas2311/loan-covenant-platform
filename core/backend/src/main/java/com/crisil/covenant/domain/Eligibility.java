package com.crisil.covenant.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * US-CM-02: helpers for the "All" wildcard semantics shared by every eligibility multi-select.
 *
 * <p>A dimension is the wildcard when it is empty or contains the {@link #ALL} sentinel; the
 * wildcard means "applies to any value" and is skipped by recommendation matching (US-REC-04).
 * {@link #normalize(List)} enforces that the wildcard and specific values never coexist — a mixed
 * set collapses to {@code ["ALL"]} (wildcard wins), and an empty set becomes {@code ["ALL"]} so a
 * dimension is never stored blank.
 */
public final class Eligibility {

    /** The synthetic wildcard sentinel stored for a dimension that applies to any value. */
    public static final String ALL = "ALL";

    private Eligibility() {
    }

    /** True when the stored set means "any value" (null/empty, or contains the ALL sentinel). */
    public static boolean isWildcard(List<String> values) {
        if (values == null || values.isEmpty()) {
            return true;
        }
        for (String value : values) {
            if (value != null && ALL.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalise a multi-select for storage: wildcard (or empty/blank) collapses to {@code ["ALL"]};
     * otherwise the distinct, blank-free specific values are kept in their given order.
     */
    public static List<String> normalize(List<String> values) {
        if (isWildcard(values)) {
            return List.of(ALL);
        }
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && !cleaned.contains(trimmed)) {
                cleaned.add(trimmed);
            }
        }
        return cleaned.isEmpty() ? List.of(ALL) : cleaned;
    }

    /**
     * Membership test for a single CP value against a covenant's set: passes when the covenant set
     * is wildcard, or the (non-null) CP value is contained (case-insensitively).
     */
    public static boolean matchesValue(List<String> covenantSet, String cpValue) {
        if (isWildcard(covenantSet)) {
            return true;
        }
        if (cpValue == null || cpValue.isBlank()) {
            return false;
        }
        return covenantSet.stream().anyMatch(v -> v != null && v.trim().equalsIgnoreCase(cpValue.trim()));
    }

    /**
     * Intersection test for multi-valued CP attributes: passes when the covenant set is wildcard, or
     * any CP value is contained in the covenant set (case-insensitively).
     */
    public static boolean intersects(List<String> covenantSet, List<String> cpValues) {
        if (isWildcard(covenantSet)) {
            return true;
        }
        if (cpValues == null || cpValues.isEmpty()) {
            return false;
        }
        for (String cpValue : cpValues) {
            if (matchesValue(covenantSet, cpValue)) {
                return true;
            }
        }
        return false;
    }
}
