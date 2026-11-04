package com.crisil.covenant.entity;

import jakarta.persistence.EntityListeners;
import com.crisil.covenant.service.CpChangeListener;
import com.crisil.covenant.entity.AuditableEntity;
import com.crisil.covenant.enums.CovenantClassification;
import com.crisil.covenant.enums.CovenantConditionType;
import com.crisil.covenant.entity.CovenantMaster;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.annotations.UuidGenerator;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.ThresholdSource;

@Entity
// Named so @EntityGraph finds it directly: with attributePaths alone, Spring Data first looks up a named
// graph, gets an IllegalArgumentException on every call, then builds the graph (load test: ~10% of CPU).
@NamedEntityGraph(name = AppliedCovenant.WITH_MASTER, attributeNodes = @NamedAttributeNode("covenantMaster"))
@EntityListeners(CpChangeListener.class)
@Table(name = "cov_applied_covenant")
public class AppliedCovenant extends AuditableEntity {

    public static final String WITH_MASTER = "AppliedCovenant.withMaster";

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "cp_id", nullable = false, length = 64)
    private String cpId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "covenant_master_id", nullable = false)
    private CovenantMaster covenantMaster;

    @Column(name = "frequency", nullable = false, length = 16)
    private Frequency frequency;

    @Column(name = "cron_expression", length = 64)
    private String cronExpression;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "status", nullable = false, length = 16)
    private AppliedCovenantStatus status = AppliedCovenantStatus.ACTIVE;

    @Column(name = "snapshot_json", nullable = false, length = 4000)
    private String snapshotJson;

    /**
     * Per-CP override of the covenant condition — same structure as the master, with thresholds
     * tuned for this CP. {@code null} means use the master's condition unchanged.
     */
    @Column(name = "condition_expression", length = 2000)
    private String conditionExpression;

    /**
     * Whether this covenant must be satisfied before drawdown (PRECEDENT) or maintained after
     * (SUBSEQUENT) <em>for this CP</em>. Captured at apply time; nullable. CP-level (US-CM), not a
     * library-definition attribute.
     */
    @Column(name = "condition_type", length = 16)
    private CovenantConditionType conditionType;

    /**
     * Whether this covenant is externally imposed (EXTERNAL) or internally set (INTERNAL) <em>for
     * this CP</em>. Captured at apply time; nullable. CP-level, not a library-definition attribute.
     */
    @Column(name = "classification", length = 16)
    private CovenantClassification classification;

    /**
     * How this CP's threshold was set (US-ML-13): {@code MASTER} (library default, no override),
     * {@code USER} (hand-edited), or {@code ML} (accepted ML recommendation). Defaults to
     * {@code MASTER} so pre-feature rows and programmatic/test construction stay valid.
     */
    @Column(name = "threshold_source", nullable = false, length = 16)
    private ThresholdSource thresholdSource = ThresholdSource.MASTER;

    public String getId() {
        return id;
    }

    public String getConditionExpression() {
        return conditionExpression;
    }

    public void setConditionExpression(String conditionExpression) {
        this.conditionExpression = conditionExpression;
    }

    public String getCpId() {
        return cpId;
    }

    public void setCpId(String cpId) {
        this.cpId = cpId;
    }

    public CovenantMaster getCovenantMaster() {
        return covenantMaster;
    }

    public void setCovenantMaster(CovenantMaster covenantMaster) {
        this.covenantMaster = covenantMaster;
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public void setFrequency(Frequency frequency) {
        this.frequency = frequency;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public AppliedCovenantStatus getStatus() {
        return status;
    }

    public void setStatus(AppliedCovenantStatus status) {
        this.status = status;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }

    public CovenantConditionType getConditionType() {
        return conditionType;
    }

    public void setConditionType(CovenantConditionType conditionType) {
        this.conditionType = conditionType;
    }

    public CovenantClassification getClassification() {
        return classification;
    }

    public void setClassification(CovenantClassification classification) {
        this.classification = classification;
    }

    public ThresholdSource getThresholdSource() {
        return thresholdSource;
    }

    public void setThresholdSource(ThresholdSource thresholdSource) {
        this.thresholdSource = thresholdSource;
    }
}
