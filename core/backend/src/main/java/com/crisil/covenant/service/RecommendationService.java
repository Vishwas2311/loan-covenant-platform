package com.crisil.covenant.service;

import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.exception.DuplicateResourceException;
import com.crisil.covenant.service.CachedCovenantAttributesService;
import com.crisil.covenant.domain.CovenantAttributes;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.util.CovenantMasterSpecifications;
import com.crisil.covenant.domain.Eligibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.quartz.CronExpression;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crisil.covenant.domain.CpEligibility;
import com.crisil.covenant.domain.RecommendedCovenant;
import com.crisil.covenant.dto.CreateCustomCovenantRequest;
import com.crisil.covenant.entity.CovenantRecommendation;
import com.crisil.covenant.enums.RecommendationStatus;
import com.crisil.covenant.repository.CovenantRecommendationRepository;

@Service
@Transactional
public class RecommendationService {

    private final CovenantMasterRepository masterRepo;
    private final CovenantRecommendationRepository recommendationRepo;
    private final CachedCovenantAttributesService attributesService;
    private final CovenantConditionValidator conditionValidator;

    public RecommendationService(CovenantMasterRepository masterRepo,
                                 CovenantRecommendationRepository recommendationRepo,
                                 CachedCovenantAttributesService attributesService,
                                 CovenantConditionValidator conditionValidator) {
        this.masterRepo = masterRepo;
        this.recommendationRepo = recommendationRepo;
        this.attributesService = attributesService;
        this.conditionValidator = conditionValidator;
    }

    /**
     * Idempotent: each call upserts {@link CovenantRecommendation} rows for the CP. Rows whose
     * existing status is {@code ACCEPTED} or {@code REJECTED} are preserved (history matters);
     * everything else is refreshed to {@code SUGGESTED} with the latest match reason.
     */
    public List<RecommendedCovenant> recommendForCp(String cpId) {
        CovenantAttributes attrs = attributesService.fetch(cpId);
        CpEligibility cp = CpEligibility.from(attrs);
        List<CovenantMaster> activeCovenants = masterRepo.findAll(
                CovenantMasterSpecifications.matching(null, null, null, true),
                Sort.by("name"));

        List<RecommendedCovenant> out = new ArrayList<>();
        for (CovenantMaster cm : activeCovenants) {
            String reason = computeMatchReason(cm, cp);
            if (reason != null) {
                upsert(cpId, cm.getId(), reason);
                out.add(RecommendedCovenant.from(cm, reason));
            }
        }
        // The CP's own custom covenants are excluded from the eligibility pool (the spec filters
        // is_custom=false) — append them so they always show in this CP's list, tagged Custom.
        for (CovenantMaster cm : masterRepo.findByOwnerCpIdAndActiveTrue(cpId)) {
            upsert(cpId, cm.getId(), "Custom covenant");
            out.add(RecommendedCovenant.from(cm, "Custom covenant"));
        }
        return out;
    }

    /**
     * Create a CP-scoped custom covenant: a backing {@link CovenantMaster} flagged
     * {@code isCustom}/{@code ownerCpId}, with eligibility copied from the CP's attributes and only
     * the front-end fields set explicitly. It is registered on the CP's recommendation list
     * ({@code MANUAL_ADD}, "Custom covenant") and returned so the wizard can pre-select it.
     *
     * <p>US-CM-24: the requested assessment type decides how {@code definition} is treated. An
     * {@code AUTO} covenant's definition is a measure formula — validated here with the same
     * {@link CovenantConditionValidator} the covenant library screen uses, with its measures extracted
     * so the engine can evaluate it automatically. A {@code MANUAL} one is free text stored verbatim.
     * The category is derived from the type so an AUTO covenant's actual/headroom render on the
     * assessment grid (which shows them for FINANCIAL covenants only).
     */
    public RecommendedCovenant createCustomForCp(String cpId, CreateCustomCovenantRequest req) {
        if (masterRepo.existsByOwnerCpIdAndNameIgnoreCase(cpId, req.name())) {
            throw new DuplicateResourceException(
                    "Custom covenant '" + req.name() + "' already exists for " + cpId);
        }
        AssessmentType assessmentType = req.safeAssessmentType();
        boolean auto = assessmentType == AssessmentType.AUTO;
        // Validate before anything is persisted, so an invalid formula leaves no covenant behind.
        if (auto) {
            conditionValidator.validateOrThrow(req.definition());
        }
        CpEligibility cp = CpEligibility.from(attributesService.fetch(cpId));

        CovenantMaster cm = new CovenantMaster();
        cm.setName(req.name());
        cm.setCategory(auto ? CovenantCategory.FINANCIAL : CovenantCategory.NON_FINANCIAL);
        cm.setDescription(req.definition());
        cm.setConditionExpression(req.definition());
        cm.setAssessmentType(assessmentType);
        cm.setReferencedMeasures(
                auto ? conditionValidator.referencedMeasures(req.definition()) : List.of());
        cm.setCustom(true);
        cm.setOwnerCpId(cpId);
        cm.setActive(true);
        cm.setDefaultFrequency(req.frequency());
        cm.setDefaultCron(validatedCron(req.frequency(), req.cronExpression()));
        // Eligibility mirrors the CP this covenant was created for (same projection the engine uses).
        cm.setIslamicBanking(cp.islamicBanking());
        cm.setBusinessSegments(Eligibility.normalize(cp.segments()));
        cm.setIndustries(Eligibility.normalize(cp.industries()));
        cm.setCountriesOfRisk(Eligibility.normalize(single(cp.countryOfRisk())));
        cm.setCountriesOfIncorporation(Eligibility.normalize(single(cp.countryOfIncorporation())));
        cm.setCreditProposalTypes(Eligibility.normalize(single(cp.creditProposalType())));
        cm.setLegalEntityTypes(Eligibility.normalize(single(cp.legalEntityType())));
        cm.setFacilityTypes(Eligibility.normalize(cp.facilityTypes()));
        cm.setCollateralTypes(Eligibility.normalize(cp.collateralTypes()));

        CovenantMaster saved = masterRepo.saveAndFlush(cm);

        CovenantRecommendation rec = new CovenantRecommendation();
        rec.setCpId(cpId);
        rec.setCovenantMasterId(saved.getId());
        rec.setMatchReason("Custom covenant");
        rec.setStatus(RecommendationStatus.MANUAL_ADD);
        recommendationRepo.save(rec);

        return RecommendedCovenant.from(saved, "Custom covenant");
    }

    private static List<String> single(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.trim());
    }

    private static String validatedCron(Frequency frequency, String cron) {
        if (frequency != Frequency.CUSTOM) {
            return null;
        }
        if (cron == null || cron.isBlank() || !CronExpression.isValidExpression(cron)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRON",
                    "Custom frequency requires a valid Quartz cron expression");
        }
        return cron.trim();
    }

    @Transactional(readOnly = true)
    public List<CovenantRecommendation> historyFor(String cpId) {
        return recommendationRepo.findByCpId(cpId);
    }

    public void markAcceptedOrManual(String cpId, String covenantMasterId, boolean fromRecommendation) {
        CovenantRecommendation row = recommendationRepo
                .findByCpIdAndCovenantMasterId(cpId, covenantMasterId)
                .orElseGet(() -> {
                    CovenantRecommendation fresh = new CovenantRecommendation();
                    fresh.setCpId(cpId);
                    fresh.setCovenantMasterId(covenantMasterId);
                    fresh.setMatchReason(fromRecommendation ? null : "Added manually");
                    return fresh;
                });
        row.setStatus(fromRecommendation ? RecommendationStatus.ACCEPTED : RecommendationStatus.MANUAL_ADD);
        recommendationRepo.save(row);
    }

    public void markRejected(String cpId, String covenantMasterId) {
        Optional<CovenantRecommendation> row =
                recommendationRepo.findByCpIdAndCovenantMasterId(cpId, covenantMasterId);
        row.ifPresent(r -> {
            r.setStatus(RecommendationStatus.REJECTED);
            recommendationRepo.save(r);
        });
    }

    private void upsert(String cpId, String covenantMasterId, String reason) {
        Optional<CovenantRecommendation> existing =
                recommendationRepo.findByCpIdAndCovenantMasterId(cpId, covenantMasterId);
        CovenantRecommendation row;
        if (existing.isPresent()) {
            row = existing.get();
            row.setMatchReason(reason);
            // Preserve ACCEPTED / REJECTED / MANUAL_ADD; only refresh state that was still SUGGESTED.
            if (row.getStatus() == RecommendationStatus.SUGGESTED) {
                row.setStatus(RecommendationStatus.SUGGESTED);
            }
        } else {
            row = new CovenantRecommendation();
            row.setCpId(cpId);
            row.setCovenantMasterId(covenantMasterId);
            row.setMatchReason(reason);
            row.setStatus(RecommendationStatus.SUGGESTED);
        }
        recommendationRepo.save(row);
    }

    /**
     * US-REC-04: a covenant fits a CP when <b>every</b> dimension passes (logical AND). A dimension
     * set to the {@code ALL} wildcard is skipped; Islamic banking is an exact match; multi-select
     * dimensions pass on intersection/membership with the CP's value(s). Returns a human-readable
     * reason listing the matched (non-wildcard) dimensions, or {@code null} when the covenant is
     * excluded.
     */
    private static String computeMatchReason(CovenantMaster cm, CpEligibility cp) {
        // Islamic banking — exact match, no wildcard.
        if (cm.isIslamicBanking() != cp.islamicBanking()) {
            return null;
        }

        List<String> reasons = new ArrayList<>();

        String segment = matchSet(cm.getBusinessSegments(), cp.segments());
        if (FAILED.equals(segment)) {
            return null;
        } else if (segment != null) {
            reasons.add("Segment: " + segment);
        }

        String industry = matchSet(cm.getIndustries(), cp.industries());
        if (FAILED.equals(industry)) {
            return null;
        } else if (industry != null) {
            reasons.add("Industry: " + industry);
        }

        String risk = matchValue(cm.getCountriesOfRisk(), cp.countryOfRisk());
        if (FAILED.equals(risk)) {
            return null;
        } else if (risk != null) {
            reasons.add("Country of risk: " + risk);
        }

        String incorporation = matchValue(cm.getCountriesOfIncorporation(), cp.countryOfIncorporation());
        if (FAILED.equals(incorporation)) {
            return null;
        } else if (incorporation != null) {
            reasons.add("Country of incorporation: " + incorporation);
        }

        String cpType = matchValue(cm.getCreditProposalTypes(), cp.creditProposalType());
        if (FAILED.equals(cpType)) {
            return null;
        } else if (cpType != null) {
            reasons.add("Credit proposal type: " + cpType);
        }

        String legalEntity = matchValue(cm.getLegalEntityTypes(), cp.legalEntityType());
        if (FAILED.equals(legalEntity)) {
            return null;
        } else if (legalEntity != null) {
            reasons.add("Legal entity type: " + legalEntity);
        }

        String facility = matchSet(cm.getFacilityTypes(), cp.facilityTypes());
        if (FAILED.equals(facility)) {
            return null;
        } else if (facility != null) {
            reasons.add("Facility type: " + facility);
        }

        String collateral = matchSet(cm.getCollateralTypes(), cp.collateralTypes());
        if (FAILED.equals(collateral)) {
            return null;
        } else if (collateral != null) {
            reasons.add("Collateral type: " + collateral);
        }

        String islamic = "Islamic Banking: " + (cm.isIslamicBanking() ? "Yes" : "No");
        if (reasons.isEmpty()) {
            return "Applies to all deals (" + islamic + ")";
        }
        return islamic + "; " + String.join("; ", reasons);
    }

    /** Sentinel distinguishing "dimension failed" from "wildcard/skipped" (null). */
    private static final String FAILED = " FAILED";

    /**
     * Intersection test for a multi-valued covenant dimension against the CP's value(s). Returns
     * {@code null} when the covenant set is wildcard (skip), {@link #FAILED} when there is no
     * overlap, otherwise the matched value(s) joined for the reason.
     */
    private static String matchSet(List<String> covenantSet, List<String> cpValues) {
        if (Eligibility.isWildcard(covenantSet)) {
            return null;
        }
        List<String> overlap = new ArrayList<>();
        if (cpValues != null) {
            for (String cpValue : cpValues) {
                if (cpValue != null && !cpValue.isBlank()
                        && covenantSet.stream().anyMatch(c -> c != null
                                && c.trim().equalsIgnoreCase(cpValue.trim()))
                        && !overlap.contains(cpValue.trim())) {
                    overlap.add(cpValue.trim());
                }
            }
        }
        return overlap.isEmpty() ? FAILED : String.join(", ", overlap);
    }

    /**
     * Membership test for a single-valued CP attribute. Returns {@code null} for wildcard (skip),
     * {@link #FAILED} when the CP value is absent or not in the covenant set, otherwise the value.
     */
    private static String matchValue(List<String> covenantSet, String cpValue) {
        if (Eligibility.isWildcard(covenantSet)) {
            return null;
        }
        return Eligibility.matchesValue(covenantSet, cpValue) ? cpValue.trim() : FAILED;
    }
}
