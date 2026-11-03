package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.entity.CovenantDisposition;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import org.junit.jupiter.api.Test;

/**
 * Branch coverage for the shared active-disposition rule (US-MON-16/20/21): a Waiver/Extension only
 * counts while it is pinned to the covenant's latest assessment.
 */
class DispositionRulesTest {

    private static final String LATEST = "asmt-latest";

    private static CovenantDisposition disp(String assessmentId, CovenantDispositionStatus status) {
        CovenantDisposition d = new CovenantDisposition();
        d.setAssessmentId(assessmentId);
        d.setStatus(status);
        return d;
    }

    @Test
    void nullDispositionDoesNotAddress() {
        assertThat(DispositionRules.addressesCurrentBreach(null, LATEST)).isFalse();
    }

    @Test
    void dispositionWithNullAssessmentIdDoesNotAddress() {
        assertThat(DispositionRules.addressesCurrentBreach(
                disp(null, CovenantDispositionStatus.WAIVER), LATEST)).isFalse();
    }

    @Test
    void dispositionAgainstOlderAssessmentDoesNotAddress() {
        assertThat(DispositionRules.addressesCurrentBreach(
                disp("asmt-old", CovenantDispositionStatus.WAIVER), LATEST)).isFalse();
    }

    @Test
    void activeWaiverAgainstLatestAddresses() {
        assertThat(DispositionRules.addressesCurrentBreach(
                disp(LATEST, CovenantDispositionStatus.WAIVER), LATEST)).isTrue();
    }

    @Test
    void activeExtensionAgainstLatestAddresses() {
        assertThat(DispositionRules.addressesCurrentBreach(
                disp(LATEST, CovenantDispositionStatus.EXTENSION), LATEST)).isTrue();
    }

    @Test
    void derivedStatusAgainstLatestDoesNotAddress() {
        // BREACHED/COMPLIED are derived states, not dispositions — even pinned to the latest assessment.
        assertThat(DispositionRules.addressesCurrentBreach(
                disp(LATEST, CovenantDispositionStatus.BREACHED), LATEST)).isFalse();
    }
}
