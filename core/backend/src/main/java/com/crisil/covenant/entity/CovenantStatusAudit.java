package com.crisil.covenant.entity;

import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.UuidGenerator;

/**
 * Append-only compliance trail for covenant status/disposition changes (US-MON-19), mirroring
 * {@link com.crisil.covenant.entity.ThresholdAudit}: one row per change capturing the
 * old→new status and extension date, the {@link CovenantStatusSource}, the rationale, and who/when.
 * Rows are never updated or deleted; {@code cov_assessment} stays immutable (US-036).
 */
@Entity
@Table(name = "cov_covenant_status_audit")
public class CovenantStatusAudit {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "cp_id", nullable = false, length = 64)
    private String cpId;

    @Column(name = "applied_covenant_id", nullable = false)
    private String appliedCovenantId;

    @Column(name = "assessment_id")
    private String assessmentId;

    @Column(name = "old_status", length = 16)
    private CovenantDispositionStatus oldStatus;

    @Column(name = "new_status", nullable = false, length = 16)
    private CovenantDispositionStatus newStatus;

    @Column(name = "old_extension_date")
    private LocalDate oldExtensionDate;

    @Column(name = "new_extension_date")
    private LocalDate newExtensionDate;

    @Column(name = "source", nullable = false, length = 16)
    private CovenantStatusSource source;

    @Column(name = "rationale", length = 512)
    private String rationale;

    @Column(name = "changed_by", nullable = false, length = 50)
    private String changedBy;

    @Column(name = "changed_date", nullable = false)
    private Instant changedDate;

    public CovenantStatusAudit() {}

    public CovenantStatusAudit(String cpId, String appliedCovenantId, String assessmentId,
                               CovenantDispositionStatus oldStatus, CovenantDispositionStatus newStatus,
                               LocalDate oldExtensionDate, LocalDate newExtensionDate,
                               CovenantStatusSource source, String rationale,
                               String changedBy, Instant changedDate) {
        this.cpId = cpId;
        this.appliedCovenantId = appliedCovenantId;
        this.assessmentId = assessmentId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.oldExtensionDate = oldExtensionDate;
        this.newExtensionDate = newExtensionDate;
        this.source = source;
        this.rationale = rationale;
        this.changedBy = changedBy;
        this.changedDate = changedDate;
    }

    public String getId() { return id; }
    public String getCpId() { return cpId; }
    public String getAppliedCovenantId() { return appliedCovenantId; }
    public String getAssessmentId() { return assessmentId; }
    public CovenantDispositionStatus getOldStatus() { return oldStatus; }
    public CovenantDispositionStatus getNewStatus() { return newStatus; }
    public LocalDate getOldExtensionDate() { return oldExtensionDate; }
    public LocalDate getNewExtensionDate() { return newExtensionDate; }
    public CovenantStatusSource getSource() { return source; }
    public String getRationale() { return rationale; }
    public String getChangedBy() { return changedBy; }
    public Instant getChangedDate() { return changedDate; }
}
