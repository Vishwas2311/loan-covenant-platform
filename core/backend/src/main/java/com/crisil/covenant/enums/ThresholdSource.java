package com.crisil.covenant.enums;

/**
 * Provenance of an applied covenant's threshold (US-ML-13):
 * <ul>
 *   <li>{@code MASTER} — no per-CP override; the covenant master's default condition is used.</li>
 *   <li>{@code USER} — a person edited the threshold for this CP.</li>
 *   <li>{@code ML} — an ML recommendation was accepted (and not subsequently hand-edited).</li>
 * </ul>
 */
public enum ThresholdSource {
    MASTER,
    USER,
    ML,
    /** Proposed from the loan agreement by the Document Processing Service (C1), confirmed by the RM. */
    EXTRACTED
}
