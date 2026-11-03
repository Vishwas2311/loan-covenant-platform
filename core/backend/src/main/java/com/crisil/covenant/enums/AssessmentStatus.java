package com.crisil.covenant.enums;

public enum AssessmentStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    /** US-ASM-05: created for a MANUAL covenant, awaiting a person's recorded outcome + note. */
    PENDING_MANUAL
}
