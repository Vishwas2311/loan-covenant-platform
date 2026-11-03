package com.crisil.covenant.enums;

public enum AssessmentResult {
    COMPLIANT,
    BREACHED,
    /** A value-only covenant (no breach formula): the value was computed and recorded. */
    NA,
    ERROR,
    PENDING_REVIEW
}
