package com.crisil.covenant.dto;

import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.DispositionApproval;
import java.time.LocalDate;

/**
 * FR-31/FR-32: one covenant that is breached or in error on a CP the caller can see.
 *
 * @param since      first day of the current run of BREACHED/ERROR results
 * @param daysOpen   days from {@code since} to today
 * @param escalate   open for at least the escalation threshold and not handled by an approved
 *                   waiver/extension
 */
public record BreachReportRow(String cpId, String appliedCovenantId, String covenantName, AssessmentResult result,
                              String computedValue, LocalDate since, long daysOpen,
                              CovenantDispositionStatus status, DispositionApproval approval, boolean escalate) {
}
