package com.crisil.covenant.entity;

import com.crisil.covenant.formula.FormulaType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/**
 * US-ASM-21: one decomposed comparison of an assessed condition, captured at assessment time.
 *
 * <p>A covenant condition is one or more comparisons (a measure/aggregation on the left, an operator,
 * and a threshold literal on the right). Each comparison is persisted here so the measure, operator,
 * threshold value and the actual (per-CP computed) value are queryable without re-parsing the
 * expression string. Rows are written once by {@link com.crisil.covenant.service.AssessmentService}
 * and never mutated (append-only), so there is no {@link AuditableEntity} lineage — they belong to
 * an immutable assessment.
 */
@Entity
@Table(name = "cov_assessment_threshold")
public class AssessmentThreshold {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assessment_id", nullable = false)
    private Assessment assessment;

    /** Position of this comparison within the expression (0-based, left to right). */
    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    /** The measure/aggregation on the non-literal side, e.g. {@code DSCR}, {@code AVG(PAT, '2y')}. */
    @Column(name = "measure", nullable = false, length = 512)
    private String measure;

    /** The comparison operator symbol, e.g. {@code >}, {@code >=}, {@code ==}. */
    @Column(name = "operator", nullable = false, length = 8)
    private String operator;

    /** The threshold literal without DSL syntax, e.g. {@code 1.5}, {@code PwC}, {@code TRUE}. */
    @Column(name = "threshold_value", nullable = false, length = 512)
    private String thresholdValue;

    /** The left-hand value evaluated against the CP's resolved data; null if it could not resolve. */
    @Column(name = "actual_value", length = 512)
    private String actualValue;

    /** Logical type of the comparison (persisted lowercase via {@code FormulaTypeConverter}). */
    @Column(name = "value_type", nullable = false, length = 16)
    private FormulaType valueType;

    protected AssessmentThreshold() {
        // for JPA
    }

    public AssessmentThreshold(int ordinal, String measure, String operator, String thresholdValue,
                               String actualValue, FormulaType valueType) {
        this.ordinal = ordinal;
        this.measure = measure;
        this.operator = operator;
        this.thresholdValue = thresholdValue;
        this.actualValue = actualValue;
        this.valueType = valueType;
    }

    public String getId() {
        return id;
    }

    public Assessment getAssessment() {
        return assessment;
    }

    void setAssessment(Assessment assessment) {
        this.assessment = assessment;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getMeasure() {
        return measure;
    }

    public String getOperator() {
        return operator;
    }

    public String getThresholdValue() {
        return thresholdValue;
    }

    public String getActualValue() {
        return actualValue;
    }

    public FormulaType getValueType() {
        return valueType;
    }
}
