package com.crisil.covenant.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A manual daily run: {@code RUNNING} until the sweep finishes, then {@code DONE} with its counts or
 * {@code FAILED} with the reason. {@code result} is null while the run is going.
 */
public record ManualRunView(String runId, String status, LocalDate runDate, String startedBy, Instant startedAt,
                            Instant finishedAt, RunResult result, String error) {
}
