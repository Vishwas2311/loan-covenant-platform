package com.crisil.covenant.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import com.crisil.covenant.enums.RecommendationStatus;

class CovenantRecommendationTest {

    @Test
    void gettersAndSettersExposeFields() {
        CovenantRecommendation r = new CovenantRecommendation();
        r.setCpId("CP-1");
        r.setCovenantMasterId("77777777-7777-7777-7777-777777777777");
        r.setMatchReason("matched on segment=RETAIL");
        r.setStatus(RecommendationStatus.ACCEPTED);

        assertThat(r.getCpId()).isEqualTo("CP-1");
        assertThat(r.getCovenantMasterId()).isEqualTo("77777777-7777-7777-7777-777777777777");
        assertThat(r.getMatchReason()).isEqualTo("matched on segment=RETAIL");
        assertThat(r.getStatus()).isEqualTo(RecommendationStatus.ACCEPTED);
        assertThat(r.getId()).isNull();
    }

    @Test
    void lifecycleHooksSetTimestamps() {
        CovenantRecommendation r = new CovenantRecommendation();
        r.onCreate();
        assertThat(r.getCreatedDate()).isNotNull();
        assertThat(r.getModifiedDate()).isNotNull();
        var firstUpdated = r.getModifiedDate();

        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        r.onUpdate();
        assertThat(r.getModifiedDate()).isAfterOrEqualTo(firstUpdated);
    }

    @Test
    void onCreateRespectsExistingTimestamps() {
        CovenantRecommendation r = new CovenantRecommendation();
        java.time.Instant fixed = java.time.Instant.parse("2026-01-01T00:00:00Z");
        // Force preset values via reflection-like access through setters... not directly accessible,
        // so we instead trigger onCreate twice and verify it doesn't overwrite.
        r.onCreate();
        var first = r.getCreatedDate();
        r.onCreate();
        assertThat(r.getCreatedDate()).isEqualTo(first);
        assertThat(fixed).isBefore(first);
    }

    @Test
    void statusEnumHasAllExpectedValues() {
        assertThat(RecommendationStatus.values())
                .contains(RecommendationStatus.SUGGESTED,
                          RecommendationStatus.ACCEPTED,
                          RecommendationStatus.REJECTED,
                          RecommendationStatus.MANUAL_ADD);
    }
}
