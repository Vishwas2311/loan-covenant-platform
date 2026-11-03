package com.crisil.covenant.dto;

/**
 * Outcome of one daily-breach run (US-MON-14). Returned to the manual trigger endpoint and logged by
 * the Quartz job.
 *
 * @param runId                the UUID correlating every {@code cov_notification_audit} row of this run
 * @param covenantsAssessed    covenants that were due on the run date and assessed (excludes skips)
 * @param cpsTouched           distinct CPs that had at least one covenant assessed this run
 * @param notificationsSent    notification calls that succeeded (2xx)
 * @param notificationsFailed  notification calls that failed (resilient fallback, non-2xx, or error)
 */
public record RunResult(String runId, int covenantsAssessed, int cpsTouched,
                        int notificationsSent, int notificationsFailed) {
}
