package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * C4: one item in the work queue: something a person must do on a CP, who has it, and by when. Open
 * tasks are created from the CP's covenant state and closed as RESOLVED when that state clears; a
 * person can also mark one DONE.
 */
@Entity
@Table(name = "cov_work_task")
public class WorkTask {

    public enum Kind { APPROVAL, BREACH, OVERDUE, REVIEW, CERTIFICATE }

    public enum Status { OPEN, DONE, RESOLVED }

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "cp_id", length = 64, nullable = false)
    private String cpId;

    @Column(name = "applied_covenant_id", length = 36)
    private String appliedCovenantId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false)
    private Kind kind;

    @Column(name = "title", length = 500, nullable = false)
    private String title;

    @Column(name = "assignee", length = 64, nullable = false)
    private String assignee;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status = Status.OPEN;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by", length = 64)
    private String closedBy;

    public WorkTask() {}

    public WorkTask(String cpId, String appliedCovenantId, Kind kind, String title, String assignee,
                    LocalDate dueDate, Instant createdAt) {
        this.cpId = cpId;
        this.appliedCovenantId = appliedCovenantId;
        this.kind = kind;
        this.title = title;
        this.assignee = assignee;
        this.dueDate = dueDate;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getCpId() { return cpId; }
    public String getAppliedCovenantId() { return appliedCovenantId; }
    public Kind getKind() { return kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String assignee) { this.assignee = assignee; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public Status getStatus() { return status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
    public String getClosedBy() { return closedBy; }

    /** Close the task: DONE by a person, or RESOLVED because what caused it has cleared. */
    public void close(Status how, String by, Instant at) {
        this.status = how;
        this.closedBy = by;
        this.closedAt = at;
    }
}
