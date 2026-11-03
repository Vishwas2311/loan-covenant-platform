package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.service.CreditProposalClient;
import com.crisil.covenant.domain.CreditProposalDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.crisil.covenant.domain.RecommendedCovenant;
import com.crisil.covenant.dto.CreateCustomCovenantRequest;
import com.crisil.covenant.dto.OnboardingRecommendationRow;
import com.crisil.covenant.service.ObligorMappingService;
import com.crisil.covenant.service.OnboardingRecommendationService;
import com.crisil.covenant.service.RecommendationService;

@RestController
@RequestMapping("/api/v1/credit-proposals")
@Tag(name = "Credit Proposals", description = "CP lookup and covenant recommendation")
public class RecommendationController {

    private final CpAccessGuard guard;

    private final CreditProposalClient creditProposalClient;
    private final RecommendationService recommendationService;
    private final OnboardingRecommendationService onboardingRecommendationService;
    private final ObligorMappingService obligorMappingService;

    public RecommendationController(CreditProposalClient creditProposalClient,
                                    RecommendationService recommendationService,
                                    OnboardingRecommendationService onboardingRecommendationService,
                                    ObligorMappingService obligorMappingService,
            CpAccessGuard guard) {
        this.guard = guard;
        this.creditProposalClient = creditProposalClient;
        this.recommendationService = recommendationService;
        this.onboardingRecommendationService = onboardingRecommendationService;
        this.obligorMappingService = obligorMappingService;
    }

    @GetMapping("/{cpId}")
    @Operation(summary = "Look up a Credit Proposal's details by CP_ID",
            description = "Fetches CP attributes from the external Credit Proposal service (cached).")
    public CreditProposalDetails lookup(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId) {
        guard.requireCp(cpId);
        CreditProposalDetails details = creditProposalClient.lookup(cpId);
        obligorMappingService.recordMapping(cpId, details.obligorId());
        return details;
    }

    @GetMapping("/{cpId}/recommendations")
    @Operation(summary = "Recommend covenants for a CP_ID based on its attributes",
            description = "Matches the CP's attributes (segment, product, exposure …) against the "
                    + "master library's eligibility rules and returns the recommended covenants.")
    public List<RecommendedCovenant> recommendations(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId) {
        guard.requireCp(cpId);
        return recommendationService.recommendForCp(cpId);
    }

    @GetMapping("/{cpId}/onboarding-recommendations")
    @Operation(summary = "Merged master + ML recommendations for a CP_ID (US-ML-20)",
            description = "Runs the master (eligibility) stage and the ML stage server-side and returns "
                    + "the fully-merged onboarding grid in one call: master rows, ML threshold overrides "
                    + "and any brand-new covenants ML adds — each tagged with its source and threshold "
                    + "provenance. Degrades to master rows only when ML is unavailable.")
    public List<OnboardingRecommendationRow> onboardingRecommendations(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId) {
        guard.requireCp(cpId);
        return onboardingRecommendationService.recommend(cpId);
    }

    @PostMapping("/{cpId}/custom-covenants")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a CP-scoped custom covenant and add it to the CP's list",
            description = "Creates a one-off covenant that exists only for this CP (not in the master "
                    + "library) and adds it to the CP's recommended set. US-CM-24: assessmentType "
                    + "decides how the definition is read — AUTO means a measure formula, validated "
                    + "here (400 INVALID_RULE_EXPRESSION when it is not) with its measures extracted "
                    + "and the covenant classified FINANCIAL; MANUAL means a free-text instruction "
                    + "stored verbatim, NON_FINANCIAL, assessed by a person. Returns 201.")
    public RecommendedCovenant createCustom(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Valid @RequestBody CreateCustomCovenantRequest request) {
        guard.requireCp(cpId);
        return recommendationService.createCustomForCp(cpId, request);
    }
}
