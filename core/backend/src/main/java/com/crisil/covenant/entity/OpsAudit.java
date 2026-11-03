package com.crisil.covenant.entity;

import com.crisil.covenant.enums.OpsAuditOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.UuidGenerator;

/**
 * One append-only record of a production-support WRITE action taken through {@code /api/v1/ops/**}.
 *
 * <p>This entity IS the audit trail, so — unlike the mutable {@code cov_*} entities — it deliberately
 * does not extend {@link AuditableEntity}: its own {@link #performedBy}/{@link #performedAt} are the
 * record, and rows are never updated. It is written in a {@code REQUIRES_NEW} transaction so the
 * outcome (including {@link OpsAuditOutcome#FAILURE}) persists even when a destructive operation rolls
 * back.
 */
@Entity
@Table(name = "cov_ops_audit")
public class OpsAudit {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_id", length = 128)
    private String targetId;

    @Column(name = "performed_by", nullable = false, length = 50)
    private String performedBy;

    @Column(name = "params_summary", length = 2000)
    private String paramsSummary;

    @Column(name = "outcome", nullable = false, length = 16)
    private OpsAuditOutcome outcome;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "performed_at", nullable = false)
    private Instant performedAt;

    public String getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(String performedBy) {
        this.performedBy = performedBy;
    }

    public String getParamsSummary() {
        return paramsSummary;
    }

    public void setParamsSummary(String paramsSummary) {
        this.paramsSummary = paramsSummary;
    }

    public OpsAuditOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(OpsAuditOutcome outcome) {
        this.outcome = outcome;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getPerformedAt() {
        return performedAt;
    }

    public void setPerformedAt(Instant performedAt) {
        this.performedAt = performedAt;
    }
}
