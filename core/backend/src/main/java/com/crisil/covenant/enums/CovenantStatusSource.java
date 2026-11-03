package com.crisil.covenant.enums;

/**
 * Provenance of a covenant disposition/status (US-MON-19), analogous to
 * {@code ThresholdSource} (MASTER/USER/ML) for ML thresholds (US-ML-13):
 * <ul>
 *   <li>{@link #SYSTEM} — derived from the stored assessment result (no human/AI action);</li>
 *   <li>{@link #USER} — a person recorded a Waiver/Extension on the CP assessment grid;</li>
 *   <li>{@link #AI} — an AI suggestion (US-MON-18) was accepted and applied.</li>
 * </ul>
 */
public enum CovenantStatusSource {
    SYSTEM,
    USER,
    AI
}
