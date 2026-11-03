package com.crisil.covenant.entity;

import com.crisil.covenant.enums.ThresholdSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.UuidGenerator;

/**
 * Append-only compliance trail for threshold provenance (US-ML-13). Two event kinds share one table:
 * <ul>
 *   <li>{@code RECOMMENDED} — one ML suggestion for a covenant on a CP (logged even when declined),
 *       carrying the current→recommended expression, confidence and rationale.</li>
 *   <li>{@code APPLIED} — a covenant was applied to the CP with an overridden threshold, carrying the
 *       resolved source (USER/ML), the master→applied expression and the applied-covenant id.</li>
 * </ul>
 * Rows are never updated or deleted.
 */
@Entity
@Table(name = "cov_threshold_audit")
public class ThresholdAudit {

    public enum Event { RECOMMENDED, APPLIED }

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "cp_id", nullable = false, length = 64)
    private String cpId;

    @Column(name = "covenant_master_id", nullable = false)
    private String covenantMasterId;

    @Column(name = "applied_covenant_id")
    private String appliedCovenantId;

    @Column(name = "source", nullable = false, length = 16)
    private ThresholdSource source;

    @Column(name = "event", nullable = false, length = 16)
    private Event event;

    @Column(name = "old_expression", length = 2000)
    private String oldExpression;

    @Column(name = "new_expression", length = 2000)
    private String newExpression;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "rationale", length = 512)
    private String rationale;

    @Column(name = "changed_by", nullable = false, length = 50)
    private String changedBy;

    @Column(name = "changed_date", nullable = false)
    private Instant changedDate;

    public ThresholdAudit() {}

    public ThresholdAudit(String cpId, String covenantMasterId, String appliedCovenantId,
                          ThresholdSource source, Event event,
                          String oldExpression, String newExpression,
                          BigDecimal confidence, String rationale,
                          String changedBy, Instant changedDate) {
        this.cpId = cpId;
        this.covenantMasterId = covenantMasterId;
        this.appliedCovenantId = appliedCovenantId;
        this.source = source;
        this.event = event;
        this.oldExpression = oldExpression;
        this.newExpression = newExpression;
        this.confidence = confidence;
        this.rationale = rationale;
        this.changedBy = changedBy;
        this.changedDate = changedDate;
    }

    public String getId() { return id; }
    public String getCpId() { return cpId; }
    public String getCovenantMasterId() { return covenantMasterId; }
    public String getAppliedCovenantId() { return appliedCovenantId; }
    public ThresholdSource getSource() { return source; }
    public Event getEvent() { return event; }
    public String getOldExpression() { return oldExpression; }
    public String getNewExpression() { return newExpression; }
    public BigDecimal getConfidence() { return confidence; }
    public String getRationale() { return rationale; }
    public String getChangedBy() { return changedBy; }
    public Instant getChangedDate() { return changedDate; }
}
