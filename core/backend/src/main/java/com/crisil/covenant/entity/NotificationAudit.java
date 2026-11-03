package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.UuidGenerator;

/**
 * Append-only compliance trail for the external notification service (US-MON-14 AC7). One row per
 * call attempt — one per (RM, WRM) pair in a daily breach run, plus a {@code FAILED} row for any
 * touched CP group that had no recipient. Every row carries the correlating {@code runId}, the exact
 * request/response bodies, HTTP status, outcome, timing and the acting user. Rows are never updated
 * or deleted.
 */
@Entity
@Table(name = "cov_notification_audit")
public class NotificationAudit {

    public enum Outcome { SUCCESS, FAILED }

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "email_to", length = 256)
    private String emailTo;

    @Column(name = "email_cc", length = 256)
    private String emailCc;

    @Column(name = "cp_ids", length = 2000)
    private String cpIds;

    @Column(name = "request_body", length = 4000)
    private String requestBody;

    @Column(name = "response_body", length = 4000)
    private String responseBody;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "outcome", nullable = false, length = 16)
    private Outcome outcome;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "changed_by", nullable = false, length = 50)
    private String changedBy;

    @Column(name = "changed_date", nullable = false)
    private Instant changedDate;

    public NotificationAudit() {}

    public NotificationAudit(String runId, String emailTo, String emailCc, String cpIds,
                             String requestBody, String responseBody, Integer httpStatus,
                             Outcome outcome, String errorMessage, Long durationMs,
                             String changedBy, Instant changedDate) {
        this.runId = runId;
        this.emailTo = emailTo;
        this.emailCc = emailCc;
        this.cpIds = cpIds;
        this.requestBody = requestBody;
        this.responseBody = responseBody;
        this.httpStatus = httpStatus;
        this.outcome = outcome;
        this.errorMessage = errorMessage;
        this.durationMs = durationMs;
        this.changedBy = changedBy;
        this.changedDate = changedDate;
    }

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getEmailTo() { return emailTo; }
    public String getEmailCc() { return emailCc; }
    public String getCpIds() { return cpIds; }
    public String getRequestBody() { return requestBody; }
    public String getResponseBody() { return responseBody; }
    public Integer getHttpStatus() { return httpStatus; }
    public Outcome getOutcome() { return outcome; }
    public String getErrorMessage() { return errorMessage; }
    public Long getDurationMs() { return durationMs; }
    public String getChangedBy() { return changedBy; }
    public Instant getChangedDate() { return changedDate; }
}
