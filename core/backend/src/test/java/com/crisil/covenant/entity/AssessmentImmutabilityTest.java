package com.crisil.covenant.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.covenant.exception.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentStatus;
import com.crisil.covenant.service.AssessmentService;

class AssessmentImmutabilityTest {

    @Test
    void notCompletedAssessmentCanBeMutatedFreely() {
        Assessment original = newCompleted();
        original.setStatus(AssessmentStatus.IN_PROGRESS);
        Assessment proposed = newCompleted();
        proposed.setResult(AssessmentResult.BREACHED);
        // Should not throw — status is not COMPLETED yet.
        AssessmentService.rejectIfImmutableFieldChanging(original, proposed);
        assertThat(true).isTrue();
    }

    @Test
    void mutatingResultOnCompletedThrows() {
        Assessment original = newCompleted();
        Assessment proposed = newCompleted();
        proposed.setResult(AssessmentResult.BREACHED);
        assertThatThrownBy(
                () -> AssessmentService.rejectIfImmutableFieldChanging(original, proposed))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Cannot modify");
    }

    @Test
    void mutatingSnapshotOnCompletedThrows() {
        Assessment original = newCompleted();
        Assessment proposed = newCompleted();
        proposed.setDataSnapshotJson("{\"DSCR\":2.0}");
        assertThatThrownBy(
                () -> AssessmentService.rejectIfImmutableFieldChanging(original, proposed))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void mutatingEvaluatedExpressionOnCompletedThrows() {
        Assessment original = newCompleted();
        Assessment proposed = newCompleted();
        proposed.setEvaluatedExpression("1.99 > 1.7");
        assertThatThrownBy(
                () -> AssessmentService.rejectIfImmutableFieldChanging(original, proposed))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void mutatingExecutedAtOnCompletedThrows() {
        Assessment original = newCompleted();
        Assessment proposed = newCompleted();
        proposed.setExecutedAt(Instant.parse("2099-01-01T00:00:00Z"));
        assertThatThrownBy(
                () -> AssessmentService.rejectIfImmutableFieldChanging(original, proposed))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void noOpUpdateOnCompletedDoesNotThrow() {
        Assessment original = newCompleted();
        Assessment proposed = newCompleted();
        AssessmentService.rejectIfImmutableFieldChanging(original, proposed);
        assertThat(true).isTrue();
    }

    @Test
    void switchingErrorMessageFromNullToValueIsAllowed() {
        Assessment original = newCompleted();
        original.setErrorMessage(null);
        Assessment proposed = newCompleted();
        proposed.setErrorMessage("now set");
        // errorMessage is not in the immutable-field set; should not throw.
        AssessmentService.rejectIfImmutableFieldChanging(original, proposed);
        assertThat(true).isTrue();
    }

    @Test
    void switchingExecutedAtFromNullToValueOnCompletedThrows() {
        Assessment original = newCompleted();
        original.setExecutedAt(null);
        Assessment proposed = newCompleted();
        proposed.setExecutedAt(Instant.parse("2026-06-05T10:00:00Z"));
        assertThatThrownBy(
                () -> AssessmentService.rejectIfImmutableFieldChanging(original, proposed))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void switchingExecutedAtFromValueToNullOnCompletedThrows() {
        Assessment original = newCompleted();
        Assessment proposed = newCompleted();
        proposed.setExecutedAt(null);
        assertThatThrownBy(
                () -> AssessmentService.rejectIfImmutableFieldChanging(original, proposed))
                .isInstanceOf(ApiException.class);
    }

    private static Assessment newCompleted() {
        Assessment a = new Assessment();
        a.setStatus(AssessmentStatus.COMPLETED);
        a.setResult(AssessmentResult.COMPLIANT);
        a.setDataSnapshotJson("{\"DSCR\":1.85}");
        a.setEvaluatedExpression("1.85 > 1.7");
        a.setExecutedAt(Instant.parse("2026-06-05T10:00:00Z"));
        return a;
    }
}
