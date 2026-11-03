package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.UuidGenerator;
import com.crisil.covenant.enums.RecommendationStatus;

@Entity
@Table(name = "cov_covenant_recommendation")
public class CovenantRecommendation {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "cp_id", nullable = false, length = 64)
    private String cpId;

    @Column(name = "covenant_master_id", nullable = false)
    private String covenantMasterId;

    @Column(name = "match_reason", length = 500)
    private String matchReason;

    @Column(name = "status", nullable = false, length = 16)
    private RecommendationStatus status = RecommendationStatus.SUGGESTED;

    @Column(name = "created_date", nullable = false)
    private Instant createdDate;

    @Column(name = "modified_date", nullable = false)
    private Instant modifiedDate;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdDate == null) createdDate = now;
        if (modifiedDate == null) modifiedDate = now;
    }

    @PreUpdate
    void onUpdate() {
        modifiedDate = Instant.now();
    }

    public String getId() { return id; }

    public String getCpId() { return cpId; }
    public void setCpId(String s) { this.cpId = s; }

    public String getCovenantMasterId() { return covenantMasterId; }
    public void setCovenantMasterId(String id) { this.covenantMasterId = id; }

    public String getMatchReason() { return matchReason; }
    public void setMatchReason(String s) { this.matchReason = s; }

    public RecommendationStatus getStatus() { return status; }
    public void setStatus(RecommendationStatus s) { this.status = s; }

    public Instant getCreatedDate() { return createdDate; }

    public Instant getModifiedDate() { return modifiedDate; }
}
