package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.exception.DuplicateResourceException;
import com.crisil.covenant.service.CachedCovenantAttributesService;
import com.crisil.covenant.domain.CovenantAttributes;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import com.crisil.covenant.domain.RecommendedCovenant;
import com.crisil.covenant.dto.CreateCustomCovenantRequest;
import com.crisil.covenant.entity.CovenantRecommendation;
import com.crisil.covenant.enums.RecommendationStatus;
import com.crisil.covenant.repository.CovenantRecommendationRepository;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private CovenantMasterRepository repository;

    @Mock
    private CovenantRecommendationRepository recommendationRepo;

    @Mock
    private CachedCovenantAttributesService attributesClient;

    @Mock
    private CovenantConditionValidator conditionValidator;

    @InjectMocks
    private RecommendationService service;

    @SuppressWarnings("unchecked")
    private void stubLibrary(CovenantMaster... covenants) {
        when(repository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(covenants));
    }

    private RecommendedCovenant only(List<RecommendedCovenant> result, String name) {
        return result.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    // --- AC9 backward compatibility: empty eligibility => wildcard, Islamic defaults No ---
    @Test
    void universalCovenantRecommendedForAnyCp() {
        stubLibrary(covenant("1", "Universal"));
        when(attributesClient.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "RETAIL", "TELECOM", Map.of()));

        List<RecommendedCovenant> result = service.recommendForCp("CP-1");

        assertThat(result).extracting(RecommendedCovenant::name).containsExactly("Universal");
        assertThat(only(result, "Universal").matchReason()).contains("Applies to all deals");
    }

    @Test
    void recommendsUniversalAndSegmentMatched() {
        CovenantMaster universal = covenant("1", "Universal");
        CovenantMaster retail = covenant("2", "Retail only");
        retail.setBusinessSegments(List.of("RETAIL"));
        CovenantMaster sme = covenant("3", "SME only");
        sme.setBusinessSegments(List.of("SME"));
        stubLibrary(universal, retail, sme);
        when(attributesClient.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "RETAIL", "Consumer", Map.of()));

        List<RecommendedCovenant> result = service.recommendForCp("CP-1");

        assertThat(result).extracting(RecommendedCovenant::name)
                .containsExactlyInAnyOrder("Universal", "Retail only");
        assertThat(only(result, "Retail only").matchReason()).contains("Segment: RETAIL");
    }

    @Test
    void noMatchesWhenSegmentMissingAndAllAreSegmentRestricted() {
        CovenantMaster retail = covenant("2", "Retail only");
        retail.setBusinessSegments(List.of("RETAIL"));
        stubLibrary(retail);
        when(attributesClient.fetch("CP-X")).thenReturn(
                new CovenantAttributes("CP-X", null, null, null));

        assertThat(service.recommendForCp("CP-X")).isEmpty();
    }

    @Test
    void cpSegmentNotInListExcludesRow() {
        CovenantMaster retail = covenant("2", "Retail only");
        retail.setBusinessSegments(List.of("RETAIL"));
        stubLibrary(retail);
        when(attributesClient.fetch("CP-S")).thenReturn(
                new CovenantAttributes("CP-S", "SME", null, null));

        assertThat(service.recommendForCp("CP-S")).isEmpty();
    }

    // --- AC1 + AC6: match on multiple dimensions, reason lists matched (non-wildcard) dimensions ---
    @Test
    void matchesOnIndustryAndCountryWithReason() {
        CovenantMaster cm = covenant("1", "Infra IN");
        cm.setIndustries(List.of("INFRASTRUCTURE"));
        cm.setCountriesOfRisk(List.of("IN"));
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(new CovenantAttributes(
                "CP-1", "CORPORATE", "INFRASTRUCTURE", Map.of("countryOfRisk", "IN")));

        List<RecommendedCovenant> result = service.recommendForCp("CP-1");

        assertThat(result).hasSize(1);
        String reason = only(result, "Infra IN").matchReason();
        assertThat(reason).contains("Industry: INFRASTRUCTURE").contains("Country of risk: IN");
        assertThat(reason).doesNotContain("Segment:"); // wildcard dimensions not listed
    }

    // --- AC2: excluded when one dimension fails ---
    @Test
    void excludedWhenCountryOfRiskDiffers() {
        CovenantMaster cm = covenant("1", "Infra IN");
        cm.setCountriesOfRisk(List.of("IN"));
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(new CovenantAttributes(
                "CP-1", "CORPORATE", "INFRASTRUCTURE", Map.of("countryOfRisk", "AE")));

        assertThat(service.recommendForCp("CP-1")).isEmpty();
    }

    // --- AC3: wildcard dimension never excludes ---
    @Test
    void wildcardDimensionDoesNotExclude() {
        CovenantMaster cm = covenant("1", "Any industry");
        cm.setCountriesOfRisk(List.of("IN")); // only this constrains
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(new CovenantAttributes(
                "CP-1", "CORPORATE", "ANYTHING", Map.of("countryOfRisk", "IN")));

        assertThat(service.recommendForCp("CP-1")).hasSize(1);
    }

    // --- AC4: Islamic banking is an exact match ---
    @Test
    void islamicMismatchExcludes() {
        CovenantMaster cm = covenant("1", "Islamic only");
        cm.setIslamicBanking(true);
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(new CovenantAttributes(
                "CP-1", "CORPORATE", "X", Map.of("islamicBanking", "false")));

        assertThat(service.recommendForCp("CP-1")).isEmpty();
    }

    @Test
    void islamicMatchRecommends() {
        CovenantMaster cm = covenant("1", "Islamic only");
        cm.setIslamicBanking(true);
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(new CovenantAttributes(
                "CP-1", "CORPORATE", "X", Map.of("islamicBanking", "yes")));

        List<RecommendedCovenant> result = service.recommendForCp("CP-1");
        assertThat(result).hasSize(1);
        assertThat(only(result, "Islamic only").matchReason()).contains("Islamic Banking: Yes");
    }

    // --- AC5: intersection within multi-valued dimensions ---
    @Test
    void facilityTypeIntersectionPasses() {
        CovenantMaster cm = covenant("1", "Facility rule");
        cm.setFacilityTypes(List.of("TERM_LOAN", "BANK_GUARANTEE"));
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(new CovenantAttributes(
                "CP-1", "CORPORATE", "X", Map.of("facilityType", "CASH_CREDIT, BANK_GUARANTEE")));

        List<RecommendedCovenant> result = service.recommendForCp("CP-1");
        assertThat(result).hasSize(1);
        assertThat(only(result, "Facility rule").matchReason()).contains("Facility type: BANK_GUARANTEE");
    }

    @Test
    void facilityTypeNoOverlapExcludes() {
        CovenantMaster cm = covenant("1", "Facility rule");
        cm.setFacilityTypes(List.of("TERM_LOAN"));
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(new CovenantAttributes(
                "CP-1", "CORPORATE", "X", Map.of("facilityType", "CASH_CREDIT")));

        assertThat(service.recommendForCp("CP-1")).isEmpty();
    }

    // --- Dependency note: a missing CP attribute fails a non-wildcard dimension ---
    @Test
    void missingCpAttributeFailsNonWildcardDimension() {
        CovenantMaster cm = covenant("1", "Needs CP type");
        cm.setCreditProposalTypes(List.of("TERM_LOAN"));
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "CORPORATE", "X", Map.of()));

        assertThat(service.recommendForCp("CP-1")).isEmpty();
    }

    // --- All dimensions constrained and matched: every dimension contributes to the reason ---
    @Test
    void allDimensionsMatchProducesFullReason() {
        CovenantMaster cm = covenant("1", "Fully constrained");
        cm.setIslamicBanking(true);
        cm.setBusinessSegments(List.of("CORPORATE"));
        cm.setIndustries(List.of("INFRASTRUCTURE"));
        cm.setCountriesOfRisk(List.of("IN"));
        cm.setCountriesOfIncorporation(List.of("AE"));
        cm.setCreditProposalTypes(List.of("TERM_LOAN"));
        cm.setLegalEntityTypes(List.of("PUBLIC_LTD"));
        cm.setFacilityTypes(List.of("CASH_CREDIT"));
        cm.setCollateralTypes(List.of("PROPERTY"));
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(new CovenantAttributes(
                "CP-1", "CORPORATE", "INFRASTRUCTURE", Map.of(
                        "islamicBanking", "yes",
                        "countryOfRisk", "IN",
                        "countryOfIncorporation", "AE",
                        "creditProposalType", "TERM_LOAN",
                        "legalEntityType", "PUBLIC_LTD",
                        "facilityType", "CASH_CREDIT",
                        "collateralType", "PROPERTY")));

        List<RecommendedCovenant> result = service.recommendForCp("CP-1");

        assertThat(result).hasSize(1);
        String reason = only(result, "Fully constrained").matchReason();
        assertThat(reason)
                .contains("Islamic Banking: Yes")
                .contains("Segment: CORPORATE")
                .contains("Industry: INFRASTRUCTURE")
                .contains("Country of risk: IN")
                .contains("Country of incorporation: AE")
                .contains("Credit proposal type: TERM_LOAN")
                .contains("Legal entity type: PUBLIC_LTD")
                .contains("Facility type: CASH_CREDIT")
                .contains("Collateral type: PROPERTY");
    }

    @Test
    void industryMismatchExcludes() {
        CovenantMaster cm = covenant("1", "Infra only");
        cm.setIndustries(List.of("INFRASTRUCTURE"));
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "CORPORATE", "TELECOM", Map.of()));
        assertThat(service.recommendForCp("CP-1")).isEmpty();
    }

    @Test
    void countryOfIncorporationMismatchExcludes() {
        CovenantMaster cm = covenant("1", "AE incorporated");
        cm.setCountriesOfIncorporation(List.of("AE"));
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(new CovenantAttributes(
                "CP-1", "CORPORATE", "X", Map.of("countryOfIncorporation", "IN")));
        assertThat(service.recommendForCp("CP-1")).isEmpty();
    }

    @Test
    void legalEntityTypeMismatchExcludes() {
        CovenantMaster cm = covenant("1", "Public ltd only");
        cm.setLegalEntityTypes(List.of("PUBLIC_LTD"));
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(new CovenantAttributes(
                "CP-1", "CORPORATE", "X", Map.of("legalEntityType", "LLP")));
        assertThat(service.recommendForCp("CP-1")).isEmpty();
    }

    @Test
    void collateralTypeMismatchExcludes() {
        CovenantMaster cm = covenant("1", "Property backed");
        cm.setCollateralTypes(List.of("PROPERTY"));
        stubLibrary(cm);
        when(attributesClient.fetch("CP-1")).thenReturn(new CovenantAttributes(
                "CP-1", "CORPORATE", "X", Map.of("collateralType", "SHARES")));
        assertThat(service.recommendForCp("CP-1")).isEmpty();
    }

    @Test
    void recommendedCovenantCopiesSegmentsDefensively() {
        CovenantMaster cm = covenant("5", "X");
        cm.setBusinessSegments(List.of("RETAIL", "SME"));
        RecommendedCovenant rc = RecommendedCovenant.from(cm, "Matched");
        assertThat(rc.businessSegments()).containsExactly("RETAIL", "SME");
        assertThat(rc.category()).isEqualTo(CovenantCategory.FINANCIAL);
        assertThat(rc.covenantId()).isEqualTo("5");
    }

    // --- Custom (CP-scoped) covenants ---

    @Test
    void cpOwnCustomCovenantAppendedToListTaggedCustom() {
        stubLibrary(); // no library matches
        when(attributesClient.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "RETAIL", "X", Map.of()));
        CovenantMaster custom = covenant("9", "Board resolution");
        custom.setCustom(true);
        custom.setOwnerCpId("CP-1");
        when(repository.findByOwnerCpIdAndActiveTrue("CP-1")).thenReturn(List.of(custom));

        List<RecommendedCovenant> result = service.recommendForCp("CP-1");

        RecommendedCovenant rc = only(result, "Board resolution");
        assertThat(rc.custom()).isTrue();
        assertThat(rc.matchReason()).isEqualTo("Custom covenant");
    }

    @Test
    void createCustomBuildsManualCpScopedMasterMirroringCpEligibility() {
        when(attributesClient.fetch("CP-1")).thenReturn(new CovenantAttributes(
                "CP-1", "RETAIL", "TELECOM", Map.of("countryOfRisk", "IN")));
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateCustomCovenantRequest req = new CreateCustomCovenantRequest(
                "Board resolution", Frequency.MONTHLY, null, "Board must approve drawdown",
                AssessmentType.MANUAL);

        RecommendedCovenant rc = service.createCustomForCp("CP-1", req);

        assertThat(rc.custom()).isTrue();

        ArgumentCaptor<CovenantMaster> captor = ArgumentCaptor.forClass(CovenantMaster.class);
        verify(repository).saveAndFlush(captor.capture());
        CovenantMaster saved = captor.getValue();
        assertThat(saved.isCustom()).isTrue();
        assertThat(saved.getOwnerCpId()).isEqualTo("CP-1");
        assertThat(saved.getAssessmentType()).isEqualTo(AssessmentType.MANUAL);
        assertThat(saved.getCategory()).isEqualTo(CovenantCategory.NON_FINANCIAL);
        assertThat(saved.getConditionExpression()).isEqualTo("Board must approve drawdown");
        assertThat(saved.getReferencedMeasures()).isEmpty();
        // eligibility mirrors the CP; unset dimensions fall back to the ALL wildcard
        assertThat(saved.getBusinessSegments()).containsExactly("RETAIL");
        assertThat(saved.getCountriesOfRisk()).containsExactly("IN");
        assertThat(saved.getCollateralTypes()).containsExactly("ALL");

        // US-CM-24 AC4: free text is never validated as a formula.
        verify(conditionValidator, never()).validateOrThrow(any());
        verify(conditionValidator, never()).referencedMeasures(any());

        ArgumentCaptor<CovenantRecommendation> rec = ArgumentCaptor.forClass(CovenantRecommendation.class);
        verify(recommendationRepo).save(rec.capture());
        assertThat(rec.getValue().getStatus()).isEqualTo(RecommendationStatus.MANUAL_ADD);
        assertThat(rec.getValue().getMatchReason()).isEqualTo("Custom covenant");
    }

    // --- US-CM-24: AUTO custom covenants ---

    /** AC4/AC5/AC6: an AUTO custom covenant is validated, its measures extracted, and it is FINANCIAL. */
    @Test
    void createCustomAutoValidatesFormulaExtractsMeasuresAndIsFinancial() {
        when(attributesClient.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "RETAIL", "TELECOM", Map.of()));
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));
        when(conditionValidator.referencedMeasures("DSCR > 1.4")).thenReturn(List.of("DSCR"));

        CreateCustomCovenantRequest req = new CreateCustomCovenantRequest(
                "Deal DSCR floor", Frequency.MONTHLY, null, "DSCR > 1.4", AssessmentType.AUTO);

        service.createCustomForCp("CP-1", req);

        verify(conditionValidator).validateOrThrow("DSCR > 1.4");

        ArgumentCaptor<CovenantMaster> captor = ArgumentCaptor.forClass(CovenantMaster.class);
        verify(repository).saveAndFlush(captor.capture());
        CovenantMaster saved = captor.getValue();
        assertThat(saved.getAssessmentType()).isEqualTo(AssessmentType.AUTO);
        assertThat(saved.getCategory()).isEqualTo(CovenantCategory.FINANCIAL);
        assertThat(saved.getReferencedMeasures()).containsExactly("DSCR");
        assertThat(saved.getConditionExpression()).isEqualTo("DSCR > 1.4");
        assertThat(saved.isCustom()).isTrue();
        assertThat(saved.getOwnerCpId()).isEqualTo("CP-1");
    }

    /** AC4: an invalid AUTO formula is rejected before anything is persisted. */
    @Test
    void createCustomAutoInvalidFormulaRejectedAndNothingPersisted() {
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RULE_EXPRESSION", "Unknown measure"))
                .when(conditionValidator).validateOrThrow("nonsense");

        CreateCustomCovenantRequest req = new CreateCustomCovenantRequest(
                "Bad formula", Frequency.MONTHLY, null, "nonsense", AssessmentType.AUTO);

        assertThatThrownBy(() -> service.createCustomForCp("CP-1", req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown measure");

        verify(repository, never()).saveAndFlush(any(CovenantMaster.class));
        verify(recommendationRepo, never()).save(any(CovenantRecommendation.class));
    }

    /** A null assessment type on the wire defaults to AUTO, mirroring the covenant library DTO. */
    @Test
    void createCustomNullAssessmentTypeDefaultsToAuto() {
        when(attributesClient.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "RETAIL", "TELECOM", Map.of()));
        when(repository.saveAndFlush(any(CovenantMaster.class))).thenAnswer(inv -> inv.getArgument(0));
        when(conditionValidator.referencedMeasures("DSCR > 1")).thenReturn(List.of("DSCR"));

        service.createCustomForCp("CP-1", new CreateCustomCovenantRequest(
                "Defaulted", Frequency.MONTHLY, null, "DSCR > 1", null));

        ArgumentCaptor<CovenantMaster> captor = ArgumentCaptor.forClass(CovenantMaster.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getAssessmentType()).isEqualTo(AssessmentType.AUTO);
    }

    @Test
    void createCustomRejectsDuplicateNameForSameCp() {
        when(repository.existsByOwnerCpIdAndNameIgnoreCase("CP-1", "Board resolution")).thenReturn(true);
        CreateCustomCovenantRequest req = new CreateCustomCovenantRequest(
                "Board resolution", Frequency.MONTHLY, null, "x", AssessmentType.MANUAL);
        assertThatThrownBy(() -> service.createCustomForCp("CP-1", req))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void createCustomCustomFrequencyRequiresValidCron() {
        when(attributesClient.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "RETAIL", "X", Map.of()));
        CreateCustomCovenantRequest req = new CreateCustomCovenantRequest(
                "Quarterly review", Frequency.CUSTOM, "not-a-cron", "definition",
                AssessmentType.MANUAL);
        assertThatThrownBy(() -> service.createCustomForCp("CP-1", req))
                .isInstanceOf(ApiException.class);
    }

    private static CovenantMaster covenant(String id, String name) {
        CovenantMaster cm = new CovenantMaster();
        try {
            Field f = CovenantMaster.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(cm, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        cm.setName(name);
        cm.setCategory(CovenantCategory.FINANCIAL);
        cm.setConditionExpression("DSCR > 1");
        cm.setActive(true);
        return cm;
    }
}
