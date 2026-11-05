package com.crisil.covenant.entity;

import com.crisil.covenant.service.CpChangeListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.UuidGenerator;

/**
 * One evidence file attached to a covenant on a CP (US-MON-17). Holds the opaque {@code fileId}
 * returned by the external file-upload service plus its display metadata; multiple rows per
 * {@code (cpId, appliedCovenantId)} are allowed and uploads are non-mandatory (AC2). Rows are
 * created and removed but never mutated, so there is no {@code AuditableEntity} lineage.
 */
@Entity
@Table(name = "cov_covenant_attachment")
@EntityListeners(CpChangeListener.class)
public class CovenantAttachment {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "cp_id", nullable = false, length = 64)
    private String cpId;

    @Column(name = "applied_covenant_id", nullable = false)
    private String appliedCovenantId;

    @Column(name = "file_id", nullable = false, length = 128)
    private String fileId;

    @Column(name = "file_name", nullable = false, length = 260)
    private String fileName;

    @Column(name = "uploaded_by", nullable = false, length = 50)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected CovenantAttachment() {
        // for JPA
    }

    public CovenantAttachment(String cpId, String appliedCovenantId, String fileId, String fileName,
                              String uploadedBy, Instant uploadedAt) {
        this.cpId = cpId;
        this.appliedCovenantId = appliedCovenantId;
        this.fileId = fileId;
        this.fileName = fileName;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public String getId() {
        return id;
    }

    public String getCpId() {
        return cpId;
    }

    public String getAppliedCovenantId() {
        return appliedCovenantId;
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
