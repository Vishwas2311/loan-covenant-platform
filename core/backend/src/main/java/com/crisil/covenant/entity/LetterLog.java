package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** C12: one borrower letter that was generated, by whom and when. */
@Entity
@Table(name = "cov_letter_log")
public class LetterLog {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "cp_id", length = 64, nullable = false)
    private String cpId;

    @Column(name = "applied_covenant_id", length = 36)
    private String appliedCovenantId;

    @Column(name = "kind", length = 24, nullable = false)
    private String kind;

    @Column(name = "created_by", length = 64, nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public LetterLog() {}

    public LetterLog(String cpId, String appliedCovenantId, String kind, String createdBy, Instant createdAt) {
        this.cpId = cpId;
        this.appliedCovenantId = appliedCovenantId;
        this.kind = kind;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getCpId() { return cpId; }
    public String getAppliedCovenantId() { return appliedCovenantId; }
    public String getKind() { return kind; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
