package com.crisil.covenant.entity;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedSubgraph;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.UuidGenerator;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentStatus;
import com.crisil.covenant.enums.AssessmentTrigger;

@Entity
// Named so @EntityGraph finds it directly: with attributePaths alone, Spring Data first looks up a named
// graph, gets an IllegalArgumentException on every call, then builds the graph (load test: ~10% of CPU).
@NamedEntityGraph(name = Assessment.WITH_COVENANT,
        attributeNodes = @NamedAttributeNode(value = "appliedCovenant", subgraph = "covenant"),
        subgraphs = @NamedSubgraph(name = "covenant", attributeNodes = @NamedAttributeNode("covenantMaster")))
@NamedEntityGraph(name = Assessment.WITH_COVENANT_AND_COMPARISONS,
        attributeNodes = {@NamedAttributeNode(value = "appliedCovenant", subgraph = "covenant"),
                          @NamedAttributeNode("comparisons")},
        subgraphs = @NamedSubgraph(name = "covenant", attributeNodes = @NamedAttributeNode("covenantMaster")))
@Table(name = "cov_assessment")
public class Assessment extends AuditableEntity {

    public static final String WITH_COVENANT = "Assessment.withCovenant";
    public static final String WITH_COVENANT_AND_COMPARISONS = "Assessment.withCovenantAndComparisons";

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applied_covenant_id", nullable = false)
    private AppliedCovenant appliedCovenant;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "data_snapshot_json", nullable = false, length = 4000)
    private String dataSnapshotJson = "{}";

    @Column(name = "evaluated_expression", length = 2000)
    private String evaluatedExpression;

    /** The covenant formula DEFINITION (raw DSL) in force when this assessment ran. */
    @Column(name = "condition_expression", length = 2000)
    private String conditionExpression;

    /** The per-CP value computed by the covenant's value formula (rendered as text). */
    @Column(name = "computed_value", length = 2000)
    private String computedValue;

    /** The Java simple type name of {@link #computedValue} (e.g. Double, String, LocalDate, Boolean). */
    @Column(name = "computed_value_type", length = 64)
    private String computedValueType;

    @Column(name = "result", nullable = false, length = 24)
    private AssessmentResult result = AssessmentResult.PENDING_REVIEW;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    /** US-ASM-05: the analyst's free-text rationale/evidence recorded when completing a MANUAL one. */
    @Column(name = "assessment_note", length = 2000)
    private String assessmentNote;

    @Column(name = "workflow_instance_id", length = 128)
    private String workflowInstanceId;

    @Column(name = "workflow_error", length = 500)
    private String workflowError;

    @Column(name = "triggered_by", nullable = false, length = 16)
    private AssessmentTrigger triggeredBy = AssessmentTrigger.SCHEDULED;

    @Column(name = "triggered_by_user", length = 128)
    private String triggeredByUser;

    @Column(name = "status", nullable = false, length = 16)
    private AssessmentStatus status = AssessmentStatus.PENDING;

    /**
     * US-ASM-21: decomposed comparisons captured at assessment time (measure / operator /
     * threshold / actual per comparison). Append-only — written once when the assessment runs and
     * ordered by their left-to-right position in the condition expression.
     */
    @OneToMany(mappedBy = "assessment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordinal ASC")
    private List<AssessmentThreshold> comparisons = new ArrayList<>();

    public String getId() { return id; }

    public AppliedCovenant getAppliedCovenant() { return appliedCovenant; }
    public void setAppliedCovenant(AppliedCovenant a) { this.appliedCovenant = a; }

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant t) { this.scheduledAt = t; }

    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant t) { this.executedAt = t; }

    public String getDataSnapshotJson() { return dataSnapshotJson; }
    public void setDataSnapshotJson(String j) { this.dataSnapshotJson = j; }

    public String getEvaluatedExpression() { return evaluatedExpression; }
    public void setEvaluatedExpression(String e) { this.evaluatedExpression = e; }

    public String getConditionExpression() { return conditionExpression; }
    public void setConditionExpression(String e) { this.conditionExpression = e; }

    public String getComputedValue() { return computedValue; }
    public void setComputedValue(String v) { this.computedValue = v; }

    public String getComputedValueType() { return computedValueType; }
    public void setComputedValueType(String t) { this.computedValueType = t; }

    public AssessmentResult getResult() { return result; }
    public void setResult(AssessmentResult r) { this.result = r; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String m) { this.errorMessage = m; }

    public String getAssessmentNote() { return assessmentNote; }
    public void setAssessmentNote(String n) { this.assessmentNote = n; }

    public String getWorkflowInstanceId() { return workflowInstanceId; }
    public void setWorkflowInstanceId(String id) { this.workflowInstanceId = id; }

    public String getWorkflowError() { return workflowError; }
    public void setWorkflowError(String e) { this.workflowError = e; }

    public AssessmentTrigger getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(AssessmentTrigger t) { this.triggeredBy = t; }

    public String getTriggeredByUser() { return triggeredByUser; }
    public void setTriggeredByUser(String u) { this.triggeredByUser = u; }

    public AssessmentStatus getStatus() { return status; }
    public void setStatus(AssessmentStatus s) { this.status = s; }

    public List<AssessmentThreshold> getComparisons() { return comparisons; }

    /** US-ASM-21: attach a decomposed comparison, keeping both sides of the relationship in sync. */
    public void addComparison(AssessmentThreshold comparison) {
        comparison.setAssessment(this);
        comparisons.add(comparison);
    }
}
