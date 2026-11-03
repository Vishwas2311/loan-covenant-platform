package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** C2: one reminder sent to a borrower about one compliance-certificate requirement. */
@Entity
@Table(name = "cov_reminder_log")
public class ReminderLog {

    public enum Kind { DUE_SOON, OVERDUE }

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "cp_id", length = 64, nullable = false)
    private String cpId;

    @Column(name = "requirement_id", length = 36, nullable = false)
    private String requirementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false)
    private Kind kind;

    @Column(name = "sent_on", nullable = false)
    private LocalDate sentOn;

    @Column(name = "sent_to", length = 254, nullable = false)
    private String sentTo;

    /** SENT or FAILED. */
    @Column(name = "outcome", length = 16, nullable = false)
    private String outcome;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ReminderLog() {}

    public ReminderLog(String cpId, String requirementId, Kind kind, LocalDate sentOn, String sentTo,
                       String outcome, String detail, Instant createdAt) {
        this.cpId = cpId;
        this.requirementId = requirementId;
        this.kind = kind;
        this.sentOn = sentOn;
        this.sentTo = sentTo;
        this.outcome = outcome;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getCpId() { return cpId; }
    public String getRequirementId() { return requirementId; }
    public Kind getKind() { return kind; }
    public LocalDate getSentOn() { return sentOn; }
    public String getSentTo() { return sentTo; }
    public String getOutcome() { return outcome; }
    public String getDetail() { return detail; }
}
