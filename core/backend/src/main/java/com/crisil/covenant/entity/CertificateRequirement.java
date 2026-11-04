package com.crisil.covenant.entity;

import jakarta.persistence.EntityListeners;
import com.crisil.covenant.service.CpChangeListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.annotations.UuidGenerator;

/**
 * FR-22: a CP must deliver a compliance certificate for {@code periodEnd} by {@code dueDate}. The state
 * (PENDING / RECEIVED / OVERDUE) is derived from the certificates on file, never stored.
 */
@Entity
@EntityListeners(CpChangeListener.class)
@Table(name = "cov_certificate_requirement")
public class CertificateRequirement extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "cp_id", nullable = false, length = 64)
    private String cpId;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "note", length = 1000)
    private String note;

    protected CertificateRequirement() {
        // for JPA
    }

    public CertificateRequirement(String cpId, LocalDate periodEnd, LocalDate dueDate, String note) {
        this.cpId = cpId;
        this.periodEnd = periodEnd;
        this.dueDate = dueDate;
        this.note = note;
    }

    public String getId() {
        return id;
    }

    public String getCpId() {
        return cpId;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
