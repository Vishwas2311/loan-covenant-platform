package com.crisil.covenant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.entity.CovenantMaster;
import java.time.Instant;
import java.util.List;

public record CovenantResponse(
        String id,
        String name,
        CovenantCategory category,
        String description,
        String conditionExpression,
        List<String> referencedMeasures,
        boolean islamicBanking,
        AssessmentType assessmentType,
        List<String> businessSegments,
        List<String> industries,
        List<String> countriesOfRisk,
        List<String> countriesOfIncorporation,
        List<String> creditProposalTypes,
        List<String> legalEntityTypes,
        List<String> facilityTypes,
        List<String> collateralTypes,
        Frequency defaultFrequency,
        String defaultCron,
        boolean custom,
        String ownerCpId,
        boolean active,
        Instant createdDate,
        String createdBy,
        Instant modifiedDate,
        String modifiedBy,
        String version,
        String policyReference,
        @JsonInclude(JsonInclude.Include.NON_NULL) RuleRecheck recheck) {

    /** The same covenant, with what re-checking its CPs after a rule change found. */
    public CovenantResponse withRecheck(RuleRecheck r) {
        return new CovenantResponse(id, name, category, description, conditionExpression, referencedMeasures,
                islamicBanking, assessmentType, businessSegments, industries, countriesOfRisk,
                countriesOfIncorporation, creditProposalTypes, legalEntityTypes, facilityTypes, collateralTypes,
                defaultFrequency, defaultCron, custom, ownerCpId, active, createdDate, createdBy, modifiedDate,
                modifiedBy, version, policyReference, r);
    }

    public static CovenantResponse from(CovenantMaster entity) {
        return new CovenantResponse(
                entity.getId(),
                entity.getName(),
                entity.getCategory(),
                entity.getDescription(),
                entity.getConditionExpression(),
                List.copyOf(entity.getReferencedMeasures()),
                entity.isIslamicBanking(),
                entity.getAssessmentType(),
                List.copyOf(entity.getBusinessSegments()),
                List.copyOf(entity.getIndustries()),
                List.copyOf(entity.getCountriesOfRisk()),
                List.copyOf(entity.getCountriesOfIncorporation()),
                List.copyOf(entity.getCreditProposalTypes()),
                List.copyOf(entity.getLegalEntityTypes()),
                List.copyOf(entity.getFacilityTypes()),
                List.copyOf(entity.getCollateralTypes()),
                entity.getDefaultFrequency(),
                entity.getDefaultCron(),
                entity.isCustom(),
                entity.getOwnerCpId(),
                entity.isActive(),
                entity.getCreatedDate(),
                entity.getCreatedBy(),
                entity.getModifiedDate(),
                entity.getModifiedBy(),
                entity.getVersion(),
                entity.getPolicyReference(),
                null);
    }
}
