package com.crisil.covenant.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.formula.FormulaType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentStatus;
import com.crisil.covenant.enums.AssessmentTrigger;

class AssessmentTest {

    @Test
    void settersAndGettersExposeFields() {
        Assessment a = new Assessment();
        AppliedCovenant applied = new AppliedCovenant();
        Instant t = Instant.parse("2026-06-04T10:00:00Z");

        a.setAppliedCovenant(applied);
        a.setScheduledAt(t);
        a.setExecutedAt(t.plusSeconds(30));
        a.setDataSnapshotJson("{\"DSCR\":1.85}");
        a.setEvaluatedExpression("1.85 > 1.7");
        a.setResult(AssessmentResult.COMPLIANT);
        a.setErrorMessage("none");
        a.setAssessmentNote("Verified evidence");
        a.setWorkflowInstanceId("wf-1");
        a.setWorkflowError(null);
        a.setTriggeredBy(AssessmentTrigger.MANUAL);
        a.setTriggeredByUser("alice");
        a.setStatus(AssessmentStatus.COMPLETED);

        assertThat(a.getAppliedCovenant()).isSameAs(applied);
        assertThat(a.getScheduledAt()).isEqualTo(t);
        assertThat(a.getExecutedAt()).isEqualTo(t.plusSeconds(30));
        assertThat(a.getDataSnapshotJson()).isEqualTo("{\"DSCR\":1.85}");
        assertThat(a.getEvaluatedExpression()).isEqualTo("1.85 > 1.7");
        assertThat(a.getResult()).isEqualTo(AssessmentResult.COMPLIANT);
        assertThat(a.getErrorMessage()).isEqualTo("none");
        assertThat(a.getAssessmentNote()).isEqualTo("Verified evidence");
        assertThat(a.getWorkflowInstanceId()).isEqualTo("wf-1");
        assertThat(a.getWorkflowError()).isNull();
        assertThat(a.getTriggeredBy()).isEqualTo(AssessmentTrigger.MANUAL);
        assertThat(a.getTriggeredByUser()).isEqualTo("alice");
        assertThat(a.getStatus()).isEqualTo(AssessmentStatus.COMPLETED);
        assertThat(a.getId()).isNull();
    }

    @Test
    void addComparisonLinksAndOrdersRows() {
        Assessment a = new Assessment();
        assertThat(a.getComparisons()).isEmpty();

        AssessmentThreshold first = new AssessmentThreshold(
                0, "DSCR", ">", "1.5", "1.85", FormulaType.NUMBER);
        AssessmentThreshold second = new AssessmentThreshold(
                1, "ICR", ">", "2", "2.5", FormulaType.NUMBER);
        a.addComparison(first);
        a.addComparison(second);

        assertThat(a.getComparisons()).containsExactly(first, second);
        assertThat(first.getAssessment()).isSameAs(a);
        assertThat(second.getAssessment()).isSameAs(a);
    }

    @Test
    void enumValuesPresent() {
        assertThat(AssessmentResult.values()).contains(
                AssessmentResult.COMPLIANT, AssessmentResult.BREACHED,
                AssessmentResult.ERROR, AssessmentResult.PENDING_REVIEW);
        assertThat(AssessmentStatus.values()).contains(
                AssessmentStatus.PENDING, AssessmentStatus.IN_PROGRESS, AssessmentStatus.COMPLETED,
                AssessmentStatus.PENDING_MANUAL);
        assertThat(AssessmentTrigger.values()).contains(
                AssessmentTrigger.SCHEDULED, AssessmentTrigger.MANUAL);
    }
}
