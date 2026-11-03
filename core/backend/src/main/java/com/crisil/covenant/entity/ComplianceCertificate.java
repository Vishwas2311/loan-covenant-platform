package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.UuidGenerator;

/**
 * One compliance-certificate file uploaded at the CP level (US-MON-17 AC4). Holds the opaque
 * {@code fileId} returned by the external file-upload service plus its display metadata; multiple
 * rows per CP are allowed and uploads are non-mandatory. Rows are created and removed but never
 * mutated.
 */
@Entity
@Table(name = "cov_compliance_certificate")
public class ComplianceCertificate {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "cp_id", nullable = false, length = 64)
    private String cpId;

    @Column(name = "file_id", nullable = false, length = 128)
    private String fileId;

    @Column(name = "file_name", nullable = false, length = 260)
    private String fileName;

    @Column(name = "uploaded_by", nullable = false, length = 50)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    /** FR-22: the reporting period end this certificate covers; null for a general certificate. */
    @Column(name = "period_end")
    private LocalDate periodEnd;

    protected ComplianceCertificate() {
        // for JPA
    }

    public ComplianceCertificate(String cpId, String fileId, String fileName, String uploadedBy,
                                 Instant uploadedAt) {
        this.cpId = cpId;
        this.fileId = fileId;
        this.fileName = fileName;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public ComplianceCertificate(String cpId, String fileId, String fileName, String uploadedBy,
                                 Instant uploadedAt, LocalDate periodEnd) {
        this(cpId, fileId, fileName, uploadedBy, uploadedAt);
        this.periodEnd = periodEnd;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public String getId() {
        return id;
    }

    public String getCpId() {
        return cpId;
    }

    public String getFileId() {
        return fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
