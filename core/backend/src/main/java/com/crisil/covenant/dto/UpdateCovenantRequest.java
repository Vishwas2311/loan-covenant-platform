package com.crisil.covenant.dto;

import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.enums.AssessmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * US-CM-01/02/03 update payload — mirrors {@link CreateCovenantRequest}. Description and assessment
 * type are mandatory; the eligibility multi-selects default to the wildcard ({@code ["ALL"]}) when
 * omitted. US-FREQ-07 adds the recommended monitoring frequency (defaults to Monthly). Convenience
 * constructors keep older callers/tests compiling.
 */
public record UpdateCovenantRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull CovenantCategory category,
        @NotBlank @Size(max = 2000) String description,
        @NotBlank @Size(max = 2000) String conditionExpression,
        List<String> businessSegments,
        boolean islamicBanking,
        @NotNull AssessmentType assessmentType,
        List<String> industries,
        List<String> countriesOfRisk,
        List<String> countriesOfIncorporation,
        List<String> creditProposalTypes,
        List<String> legalEntityTypes,
        List<String> facilityTypes,
        List<String> collateralTypes,
        Frequency defaultFrequency,
        @Size(max = 64) String defaultCron,
        @Size(max = 500) String policyReference) implements CovenantWriteRequest {

    public UpdateCovenantRequest(String name, CovenantCategory category, String description, String conditionExpression,
            List<String> businessSegments, boolean islamicBanking, AssessmentType assessmentType,
            List<String> industries, List<String> countriesOfRisk, List<String> countriesOfIncorporation,
            List<String> creditProposalTypes, List<String> legalEntityTypes, List<String> facilityTypes,
            List<String> collateralTypes, Frequency defaultFrequency, String defaultCron) {
        this(name, category, description, conditionExpression, businessSegments, islamicBanking, assessmentType,
                industries, countriesOfRisk, countriesOfIncorporation, creditProposalTypes, legalEntityTypes,
                facilityTypes, collateralTypes, defaultFrequency, defaultCron, null);
    }

    /** Pre-eligibility constructor: conventional (Islamic=No), AUTO, every dimension = wildcard. */
    public UpdateCovenantRequest(String name, CovenantCategory category,
                                 String description, String conditionExpression,
                                 List<String> businessSegments) {
        this(name, category, description, conditionExpression, businessSegments,
                false, AssessmentType.AUTO, null, null, null, null, null, null, null,
                Frequency.MONTHLY, null);
    }

    /** Pre-frequency constructor (US-FREQ-07): default frequency = Monthly, no custom cron. */
    public UpdateCovenantRequest(String name, CovenantCategory category,
                                 String description, String conditionExpression,
                                 List<String> businessSegments, boolean islamicBanking,
                                 AssessmentType assessmentType, List<String> industries,
                                 List<String> countriesOfRisk, List<String> countriesOfIncorporation,
                                 List<String> creditProposalTypes, List<String> legalEntityTypes,
                                 List<String> facilityTypes, List<String> collateralTypes) {
        this(name, category, description, conditionExpression, businessSegments,
                islamicBanking, assessmentType, industries, countriesOfRisk, countriesOfIncorporation,
                creditProposalTypes, legalEntityTypes, facilityTypes, collateralTypes,
                Frequency.MONTHLY, null);
    }

    public List<String> safeBusinessSegments() {
        return businessSegments == null ? List.of() : businessSegments;
    }

    public AssessmentType safeAssessmentType() {
        return assessmentType == null ? AssessmentType.AUTO : assessmentType;
    }

    public Frequency safeDefaultFrequency() {
        return defaultFrequency == null ? Frequency.MONTHLY : defaultFrequency;
    }

    public List<String> safeIndustries() {
        return industries == null ? List.of() : industries;
    }

    public List<String> safeCountriesOfRisk() {
        return countriesOfRisk == null ? List.of() : countriesOfRisk;
    }

    public List<String> safeCountriesOfIncorporation() {
        return countriesOfIncorporation == null ? List.of() : countriesOfIncorporation;
    }

    public List<String> safeCreditProposalTypes() {
        return creditProposalTypes == null ? List.of() : creditProposalTypes;
    }

    public List<String> safeLegalEntityTypes() {
        return legalEntityTypes == null ? List.of() : legalEntityTypes;
    }

    public List<String> safeFacilityTypes() {
        return facilityTypes == null ? List.of() : facilityTypes;
    }

    public List<String> safeCollateralTypes() {
        return collateralTypes == null ? List.of() : collateralTypes;
    }
}
