package com.crisil.covenant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.domain.CovenantAttributes;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CpEligibilityTest {

    @Test
    void extractsEveryDimensionFromAttributes() {
        CpEligibility cp = CpEligibility.from(new CovenantAttributes("CP-1", "CORPORATE", "OIL_GAS",
                Map.of(
                        "islamicBanking", "true",
                        "industry", "INFRASTRUCTURE",
                        "countryOfRisk", "IN",
                        "countryOfIncorporation", "AE",
                        "creditProposalType", "TERM_LOAN",
                        "legalEntityType", "PUBLIC_LTD",
                        "facilityType", "CASH_CREDIT, BANK_GUARANTEE",
                        "collateralType", "PROPERTY|RECEIVABLES")));

        assertThat(cp.islamicBanking()).isTrue();
        assertThat(cp.segments()).containsExactly("CORPORATE");
        assertThat(cp.industries()).containsExactly("INFRASTRUCTURE");
        assertThat(cp.countryOfRisk()).isEqualTo("IN");
        assertThat(cp.countryOfIncorporation()).isEqualTo("AE");
        assertThat(cp.creditProposalType()).isEqualTo("TERM_LOAN");
        assertThat(cp.legalEntityType()).isEqualTo("PUBLIC_LTD");
        assertThat(cp.facilityTypes()).containsExactly("CASH_CREDIT", "BANK_GUARANTEE");
        assertThat(cp.collateralTypes()).containsExactly("PROPERTY", "RECEIVABLES");
    }

    @Test
    void industryFallsBackToSectorWhenNoIndustryAttribute() {
        CpEligibility cp = CpEligibility.from(
                new CovenantAttributes("CP-1", "CORPORATE", "OIL_GAS", Map.of()));
        assertThat(cp.industries()).containsExactly("OIL_GAS");
    }

    @Test
    void islamicBankingAliasesAndFalsehoods() {
        assertThat(CpEligibility.from(attrs(Map.of("shariah", "yes"))).islamicBanking()).isTrue();
        assertThat(CpEligibility.from(attrs(Map.of("islamic", "Y"))).islamicBanking()).isTrue();
        assertThat(CpEligibility.from(attrs(Map.of("islamicBanking", "1"))).islamicBanking()).isTrue();
        assertThat(CpEligibility.from(attrs(Map.of("islamicBanking", "no"))).islamicBanking()).isFalse();
        assertThat(CpEligibility.from(attrs(Map.of())).islamicBanking()).isFalse();
    }

    @Test
    void missingAttributesYieldEmptyOrNull() {
        CpEligibility cp = CpEligibility.from(new CovenantAttributes("CP-1", null, null, null));
        assertThat(cp.segments()).isEmpty();
        assertThat(cp.industries()).isEmpty();
        assertThat(cp.countryOfRisk()).isNull();
        assertThat(cp.facilityTypes()).isEmpty();
        assertThat(cp.collateralTypes()).isEmpty();
    }

    @Test
    void blankAttributeValuesAreIgnored() {
        CpEligibility cp = CpEligibility.from(attrs(Map.of(
                "countryOfRisk", "   ",
                "facilityType", "  ",
                "industry", " ")));
        assertThat(cp.countryOfRisk()).isNull();
        assertThat(cp.facilityTypes()).isEmpty();
        assertThat(cp.industries()).containsExactly("X"); // blank industry -> falls back to sector
    }

    private static CovenantAttributes attrs(Map<String, String> map) {
        return new CovenantAttributes("CP-1", "CORPORATE", "X", map);
    }
}
