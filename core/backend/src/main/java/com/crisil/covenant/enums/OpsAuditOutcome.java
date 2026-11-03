package com.crisil.covenant.enums;

/**
 * The result of a production-support WRITE action recorded in {@code cov_ops_audit}. Persisted in
 * lower case (via {@code OpsAuditOutcomeConverter}) to match the table's {@code CHECK} constraint,
 * while the Java constants stay upper case so JSON payloads are unaffected.
 */
public enum OpsAuditOutcome {
    /** The action completed as intended. */
    SUCCESS,
    /** The action raised or was rejected; the row records the failure for the trail. */
    FAILURE
}
