package com.crisil.covenant.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.enums.CovenantCategory;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.crisil.covenant.enums.AssessmentType;

class CovenantMasterTest {

    @Test
    void settersAndGettersWork() {
        CovenantMaster entity = new CovenantMaster();
        entity.setName("DSCR");
        entity.setCategory(CovenantCategory.FINANCIAL);
        entity.setDescription("DSCR > threshold");
        entity.setConditionExpression("DSCR > 1.2");
        entity.setReferencedMeasures(List.of("DSCR"));
        entity.setBusinessSegments(List.of("RETAIL", "SME"));
        entity.setActive(true);

        assertThat(entity.getName()).isEqualTo("DSCR");
        assertThat(entity.getCategory()).isEqualTo(CovenantCategory.FINANCIAL);
        assertThat(entity.getDescription()).isEqualTo("DSCR > threshold");
        assertThat(entity.getConditionExpression()).isEqualTo("DSCR > 1.2");
        assertThat(entity.getReferencedMeasures()).containsExactly("DSCR");
        assertThat(entity.getBusinessSegments()).containsExactly("RETAIL", "SME");
        assertThat(entity.isActive()).isTrue();
        assertThat(entity.getId()).isNull();
    }

    @Test
    void eligibilityAndClassificationFieldsRoundTrip() {
        CovenantMaster entity = new CovenantMaster();
        entity.setIslamicBanking(true);
        entity.setAssessmentType(AssessmentType.MANUAL);
        entity.setIndustries(List.of("INFRASTRUCTURE"));
        entity.setCountriesOfRisk(List.of("IN"));
        entity.setCountriesOfIncorporation(List.of("AE"));
        entity.setCreditProposalTypes(List.of("TERM_LOAN"));
        entity.setLegalEntityTypes(List.of("PUBLIC_LTD"));
        entity.setFacilityTypes(List.of("CASH_CREDIT", "OVERDRAFT"));
        entity.setCollateralTypes(List.of("PROPERTY"));

        assertThat(entity.isIslamicBanking()).isTrue();
        assertThat(entity.getAssessmentType()).isEqualTo(AssessmentType.MANUAL);
        assertThat(entity.getIndustries()).containsExactly("INFRASTRUCTURE");
        assertThat(entity.getCountriesOfRisk()).containsExactly("IN");
        assertThat(entity.getCountriesOfIncorporation()).containsExactly("AE");
        assertThat(entity.getCreditProposalTypes()).containsExactly("TERM_LOAN");
        assertThat(entity.getLegalEntityTypes()).containsExactly("PUBLIC_LTD");
        assertThat(entity.getFacilityTypes()).containsExactly("CASH_CREDIT", "OVERDRAFT");
        assertThat(entity.getCollateralTypes()).containsExactly("PROPERTY");
    }

    @Test
    void newEntityDefaultsToAutoAndConventional() {
        CovenantMaster entity = new CovenantMaster();
        assertThat(entity.isIslamicBanking()).isFalse();
        assertThat(entity.getAssessmentType()).isEqualTo(AssessmentType.AUTO);
        assertThat(entity.getIndustries()).isEmpty();
    }

    @Test
    void assessmentTypeNullFallsBackToAuto() {
        CovenantMaster entity = new CovenantMaster();
        entity.setAssessmentType(null);
        assertThat(entity.getAssessmentType()).isEqualTo(AssessmentType.AUTO);
    }

    @Test
    void nullListsAreNormalisedToEmpty() {
        CovenantMaster entity = new CovenantMaster();
        entity.setReferencedMeasures(null);
        entity.setBusinessSegments(null);
        entity.setIndustries(null);
        entity.setCountriesOfRisk(null);
        entity.setCountriesOfIncorporation(null);
        entity.setCreditProposalTypes(null);
        entity.setLegalEntityTypes(null);
        entity.setFacilityTypes(null);
        entity.setCollateralTypes(null);

        assertThat(entity.getReferencedMeasures()).isEmpty();
        assertThat(entity.getBusinessSegments()).isEmpty();
        assertThat(entity.getIndustries()).isEmpty();
        assertThat(entity.getCountriesOfRisk()).isEmpty();
        assertThat(entity.getCountriesOfIncorporation()).isEmpty();
        assertThat(entity.getCreditProposalTypes()).isEmpty();
        assertThat(entity.getLegalEntityTypes()).isEmpty();
        assertThat(entity.getFacilityTypes()).isEmpty();
        assertThat(entity.getCollateralTypes()).isEmpty();
    }

    @Test
    void listsAreDefensivelyCopied() {
        CovenantMaster entity = new CovenantMaster();

        var referenced = new java.util.ArrayList<>(List.of("DSCR"));
        entity.setReferencedMeasures(referenced);
        referenced.add("ICR");
        assertThat(entity.getReferencedMeasures()).containsExactly("DSCR");

        var segments = new java.util.ArrayList<>(List.of("RETAIL"));
        entity.setBusinessSegments(segments);
        segments.add("SME");
        assertThat(entity.getBusinessSegments()).containsExactly("RETAIL");
    }
}
