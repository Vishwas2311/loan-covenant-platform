package com.crisil.covenant.enums;

/**
 * US-CM-03: how a covenant's condition is assessed.
 *
 * <ul>
 *   <li>{@link #AUTO} — the condition is a measure formula; it is validated on save and evaluated
 *       automatically by the engine.</li>
 *   <li>{@link #MANUAL} — the condition is free-text instruction; it is stored verbatim, never
 *       validated as a formula, and assessed by a person (US-ASM-05).</li>
 * </ul>
 */
public enum AssessmentType {
    AUTO,
    MANUAL
}
