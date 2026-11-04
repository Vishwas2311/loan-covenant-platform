package com.crisil.covenant.entity;

import jakarta.persistence.EntityListeners;
import com.crisil.covenant.service.CpChangeListener;
import com.crisil.covenant.entity.AuditableEntity;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.enums.DispositionApproval;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.UuidGenerator;

/**
 * The current RM disposition of one covenant on one CP (US-MON-16). Exactly one row per
 * {@code (cpId, appliedCovenantId)} pair, updated in place; every change also appends a
 * {@link com.crisil.covenant.entity.CovenantStatusAudit} row (US-MON-19). This is layered on
 * top of the assessment result and never mutates {@code cov_assessment}.
 */
@Entity
@EntityListeners(CpChangeListener.class)
@Table(name = "cov_covenant_disposition",
        uniqueConstraints = @UniqueConstraint(name = "uk_covenant_disposition",
                columnNames = {"cp_id", "applied_covenant_id"}))
public class CovenantDisposition extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "cp_id", nullable = false, length = 64)
    private String cpId;

    @Column(name = "applied_covenant_id", nullable = false)
    private String appliedCovenantId;

    /** The breach assessment this disposition addresses; lets a fresh assessment supersede it. */
    @Column(name = "assessment_id")
    private String assessmentId;

    @Column(name = "status", nullable = false, length = 16)
    private CovenantDispositionStatus status;

    @Column(name = "extension_date")
    private LocalDate extensionDate;

    @Column(name = "status_source", nullable = false, length = 16)
    private CovenantStatusSource statusSource;

    @Column(name = "note", length = 1000)
    private String note;

    /** FR-12: REQUESTED until an approver decides; legacy rows are APPROVED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 16)
    private DispositionApproval approvalStatus = DispositionApproval.APPROVED;

    @Column(name = "requested_by", length = 50)
    private String requestedBy;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    public String getId() {
        return id;
    }

    public String getCpId() {
        return cpId;
    }

    public void setCpId(String cpId) {
        this.cpId = cpId;
    }

    public String getAppliedCovenantId() {
        return appliedCovenantId;
    }

    public void setAppliedCovenantId(String appliedCovenantId) {
        this.appliedCovenantId = appliedCovenantId;
    }

    public String getAssessmentId() {
        return assessmentId;
    }

    public void setAssessmentId(String assessmentId) {
        this.assessmentId = assessmentId;
    }

    public CovenantDispositionStatus getStatus() {
        return status;
    }

    public void setStatus(CovenantDispositionStatus status) {
        this.status = status;
    }

    public LocalDate getExtensionDate() {
        return extensionDate;
    }

    public void setExtensionDate(LocalDate extensionDate) {
        this.extensionDate = extensionDate;
    }

    public CovenantStatusSource getStatusSource() {
        return statusSource;
    }

    public void setStatusSource(CovenantStatusSource statusSource) {
        this.statusSource = statusSource;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public DispositionApproval getApprovalStatus() {
        return approvalStatus;
    }

    public void setApprovalStatus(DispositionApproval approvalStatus) {
        this.approvalStatus = approvalStatus;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public void setDecisionNote(String decisionNote) {
        this.decisionNote = decisionNote;
    }
}
