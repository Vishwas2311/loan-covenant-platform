package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** C1: a certificate or statement a borrower sent through the portal. */
@Entity
@Table(name = "cov_portal_submission")
public class PortalSubmission {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "cp_id", length = 64, nullable = false)
    private String cpId;

    /** CERTIFICATE or STATEMENT. */
    @Column(name = "kind", length = 16, nullable = false)
    private String kind;

    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "submitted_by", length = 64, nullable = false)
    private String submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "note", length = 500)
    private String note;

    /** The stored attachment or DPS document it became. */
    @Column(name = "ref_id", length = 64)
    private String refId;

    public PortalSubmission() {}

    public PortalSubmission(String cpId, String kind, String fileName, LocalDate periodEnd, String submittedBy,
                            Instant submittedAt, String note, String refId) {
        this.cpId = cpId;
        this.kind = kind;
        this.fileName = fileName;
        this.periodEnd = periodEnd;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.note = note;
        this.refId = refId;
    }

    public String getId() { return id; }
    public String getCpId() { return cpId; }
    public String getKind() { return kind; }
    public String getFileName() { return fileName; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public String getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public String getNote() { return note; }
    public String getRefId() { return refId; }
}
