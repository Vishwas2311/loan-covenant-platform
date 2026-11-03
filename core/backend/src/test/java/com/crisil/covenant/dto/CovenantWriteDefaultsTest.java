package com.crisil.covenant.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.enums.Frequency;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Omitted fields on a covenant create/update fall back to their defaults. */
class CovenantWriteDefaultsTest {

    private static void assertDefaults(CovenantWriteRequest r) {
        assertThat(r.safeAssessmentType()).isEqualTo(AssessmentType.AUTO);
        assertThat(r.safeDefaultFrequency()).isEqualTo(Frequency.MONTHLY);
        assertThat(r.safeBusinessSegments()).isEmpty();
        assertThat(r.safeIndustries()).isEmpty();
        assertThat(r.safeCountriesOfRisk()).isEmpty();
        assertThat(r.safeCountriesOfIncorporation()).isEmpty();
        assertThat(r.safeCreditProposalTypes()).isEmpty();
        assertThat(r.safeLegalEntityTypes()).isEmpty();
        assertThat(r.safeFacilityTypes()).isEmpty();
        assertThat(r.safeCollateralTypes()).isEmpty();
        assertThat(r.policyReference()).isNull();
    }

    @Test
    void nullListsAndTypesFallBackToDefaults() {
        assertDefaults(new UpdateCovenantRequest("n", CovenantCategory.FINANCIAL, "d", "DSCR >= 1", null, false, null,
                null, null, null, null, null, null, null, null, null));
        assertDefaults(new CreateCovenantRequest("n", CovenantCategory.FINANCIAL, "d", "DSCR >= 1", null, false, null,
                null, null, null, null, null, null, null, null, null));
    }

    @Test
    void givenValuesAreKept() {
        UpdateCovenantRequest r = new UpdateCovenantRequest("n", CovenantCategory.FINANCIAL, "d", "DSCR >= 1",
                List.of("CORPORATE"), true, AssessmentType.MANUAL, List.of("STEEL"), List.of("IN"), List.of("IN"),
                List.of("NEW"), List.of("PVT"), List.of("TL"), List.of("LAND"), Frequency.QUARTERLY, null,
                "Credit Policy 4.2");
        assertThat(r.safeAssessmentType()).isEqualTo(AssessmentType.MANUAL);
        assertThat(r.safeDefaultFrequency()).isEqualTo(Frequency.QUARTERLY);
        assertThat(r.safeIndustries()).containsExactly("STEEL");
        assertThat(r.safeCollateralTypes()).containsExactly("LAND");
        assertThat(r.policyReference()).isEqualTo("Credit Policy 4.2");
    }
}
