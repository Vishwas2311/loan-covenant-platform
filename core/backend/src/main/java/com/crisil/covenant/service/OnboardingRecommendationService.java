package com.crisil.covenant.service;

import com.crisil.covenant.domain.RecommendedCovenant;
import com.crisil.covenant.dto.ExtractionRecommendation;
import com.crisil.covenant.dto.MlCovenantContext;
import com.crisil.covenant.dto.MlThresholdRecommendation;
import com.crisil.covenant.dto.OnboardingRecommendationRow;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.CovenantMasterRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-ML-20: composes the master (eligibility) recommendations and the ML recommendations into a
 * single merged grid for the onboarding wizard, so the front end fetches the pre-populated Review
 * &amp; Configure grid with one call instead of orchestrating the merge (and a per-covenant look-up
 * per new ML covenant) in the browser.
 *
 * <p>Pipeline: master recommendations → already-applied guard → ML (given the master conditions as
 * the in-scope baseline) → merge. ML may <em>override</em> a master row's threshold or <em>add</em>
 * a brand-new covenant (AUTO or MANUAL). ML degrades gracefully to no recommendations, in which case
 * the grid is simply the master rows.
 *
 * <p>C1: covenants the Document Processing Service extracted from the CP's loan agreements are merged
 * last. The agreement is the contract, so an extracted threshold wins over master and ML; extracted
 * library covenants not already in the grid are added, and unmatched terms are added as custom
 * candidates. An unavailable DPS leaves the grid as master + ML.
 */
@Service
@Transactional
public class OnboardingRecommendationService {

    private final RecommendationService recommendationService;
    private final MlThresholdService mlThresholdService;
    private final AppliedCovenantRepository appliedRepo;
    private final CovenantMasterRepository masterRepo;
    private final ExtractionRecommendationService extractionService;

    public OnboardingRecommendationService(RecommendationService recommendationService,
                                           MlThresholdService mlThresholdService,
                                           AppliedCovenantRepository appliedRepo,
                                           CovenantMasterRepository masterRepo,
                                           ExtractionRecommendationService extractionService) {
        this.recommendationService = recommendationService;
        this.mlThresholdService = mlThresholdService;
        this.appliedRepo = appliedRepo;
        this.masterRepo = masterRepo;
        this.extractionService = extractionService;
    }

    public List<OnboardingRecommendationRow> recommend(String cpId) {
        List<RecommendedCovenant> masters = recommendationService.recommendForCp(cpId);

        Set<String> appliedMasterIds = appliedRepo.findByCpIdOrderByIdAsc(cpId).stream()
                .map(a -> a.getCovenantMaster().getId())
                .collect(Collectors.toSet());

        // Master details (condition + assessment type) for every recommended id, in one query.
        Map<String, CovenantMaster> masterById = masterRepo.findAllById(
                        masters.stream().map(RecommendedCovenant::covenantId).toList()).stream()
                .collect(Collectors.toMap(CovenantMaster::getId, m -> m, (a, b) -> a, LinkedHashMap::new));

        // In-scope items for the ML stage: the recommended covenants not already on the CP, carrying
        // the master's condition as the baseline the ML override is compared against.
        List<MlCovenantContext> items = masters.stream()
                .filter(m -> !appliedMasterIds.contains(m.covenantId()))
                .map(m -> new MlCovenantContext(
                        m.covenantId(), m.name(), conditionOf(masterById.get(m.covenantId())), null))
                .toList();

        Map<String, MlThresholdRecommendation> mlById = mlThresholdService.recommend(cpId, items).stream()
                .collect(Collectors.toMap(
                        MlThresholdRecommendation::covenantMasterId, r -> r, (a, b) -> a, LinkedHashMap::new));

        List<OnboardingRecommendationRow> rows = new ArrayList<>();

        // 1) Master rows, each possibly overridden by an ML threshold recommendation.
        for (RecommendedCovenant m : masters) {
            CovenantMaster master = masterById.get(m.covenantId());
            AssessmentType type = master != null ? master.getAssessmentType() : AssessmentType.AUTO;
            MlThresholdRecommendation ml = mlById.get(m.covenantId());
            boolean overridden = ml != null && ml.recommendedExpression() != null
                    && type != AssessmentType.MANUAL;
            rows.add(new OnboardingRecommendationRow(
                    m.covenantId(),
                    m.name(),
                    m.matchReason(),
                    type,
                    m.defaultFrequency(),
                    m.defaultCron(),
                    m.custom(),
                    appliedMasterIds.contains(m.covenantId()),
                    overridden ? "ML" : "RECOMMENDED",
                    overridden ? ml.recommendedExpression() : null,
                    overridden ? ThresholdSource.ML : ThresholdSource.MASTER,
                    overridden ? ml.recommendedExpression() : null));
        }

        // 2) Brand-new covenants ML added (ids not among the master recommendations).
        for (MlThresholdRecommendation ml : mlById.values()) {
            if (masterById.containsKey(ml.covenantMasterId())) {
                continue; // already handled as an override above
            }
            CovenantMaster master = masterRepo.findById(ml.covenantMasterId())
                    .filter(CovenantMaster::isActive)
                    .orElse(null);
            if (master == null) {
                continue; // MlThresholdService only surfaces active masters; defensive guard
            }
            boolean manual = master.getAssessmentType() == AssessmentType.MANUAL;
            String override = manual ? null : ml.recommendedExpression();
            rows.add(new OnboardingRecommendationRow(
                    master.getId(),
                    master.getName(),
                    ml.rationale() != null && !ml.rationale().isBlank() ? ml.rationale() : "ML recommended",
                    master.getAssessmentType(),
                    master.getDefaultFrequency(),
                    master.getDefaultCron(),
                    master.isCustom(),
                    appliedMasterIds.contains(master.getId()),
                    "ML",
                    override,
                    manual ? ThresholdSource.MASTER : ThresholdSource.ML,
                    override));
        }

        return mergeExtracted(rows, extractionService.recommend(cpId));
    }

    private List<OnboardingRecommendationRow> mergeExtracted(List<OnboardingRecommendationRow> rows,
                                                             List<ExtractionRecommendation> extracted) {
        if (extracted.isEmpty()) {
            return rows;
        }
        Map<String, ExtractionRecommendation> byMaster = new LinkedHashMap<>();
        List<ExtractionRecommendation> customs = new ArrayList<>();
        for (ExtractionRecommendation e : extracted) {
            if (e.covenantMasterId() == null) {
                customs.add(e);
            } else {
                byMaster.putIfAbsent(e.covenantMasterId(), e);
            }
        }

        List<OnboardingRecommendationRow> merged = new ArrayList<>();
        for (OnboardingRecommendationRow row : rows) {
            ExtractionRecommendation e = row.covenantId() == null ? null : byMaster.remove(row.covenantId());
            merged.add(e == null ? row : withExtraction(row, e));
        }

        // Library covenants the agreement contains that neither the master nor the ML stage proposed.
        for (ExtractionRecommendation e : byMaster.values()) {
            CovenantMaster master = masterRepo.findById(e.covenantMasterId()).orElse(null);
            if (master == null) {
                continue; // ExtractionRecommendationService only keeps active masters; defensive guard
            }
            Frequency frequency = frequencyOf(e.frequency(), master.getDefaultFrequency());
            OnboardingRecommendationRow base = new OnboardingRecommendationRow(
                    master.getId(), master.getName(), reasonOf(e), master.getAssessmentType(),
                    frequency, cronOf(frequency, master),
                    master.isCustom(), false, "EXTRACTED", null, ThresholdSource.MASTER, null);
            merged.add(withExtraction(base, e));
        }

        // A custom covenant the RM already created for this CP from the agreement comes back as a master row.
        Set<String> customNames = rows.stream().filter(OnboardingRecommendationRow::custom)
                .map(OnboardingRecommendationRow::name).collect(Collectors.toSet());
        for (ExtractionRecommendation e : customs) {
            ExtractionRecommendation.CustomCovenant custom = e.customCovenant();
            if (customNames.contains(custom.name())) {
                continue;
            }
            AssessmentType type = e.recommendedExpression() != null
                    && !"MANUAL".equals(custom.assessmentType())
                    ? AssessmentType.AUTO : AssessmentType.MANUAL;
            Frequency frequency = frequencyOf(e.frequency(), Frequency.QUARTERLY);
            merged.add(new OnboardingRecommendationRow(
                    null, custom.name(), reasonOf(e), type, frequency, frequency.defaultCron(),
                    true, false, "EXTRACTED_CUSTOM", e.recommendedExpression(),
                    e.recommendedExpression() != null ? ThresholdSource.EXTRACTED : ThresholdSource.MASTER,
                    null, e.recommendedExpression(), e.confidence(), e.source(), custom.definition()));
        }
        return merged;
    }

    /** The agreement's threshold replaces any master/ML one; a MANUAL covenant keeps no override. */
    private static OnboardingRecommendationRow withExtraction(OnboardingRecommendationRow row,
                                                              ExtractionRecommendation e) {
        boolean auto = row.assessmentType() != AssessmentType.MANUAL && e.recommendedExpression() != null;
        Frequency frequency = frequencyOf(e.frequency(), row.defaultFrequency());
        String cron = frequency == row.defaultFrequency() ? row.defaultCron() : frequency.defaultCron();
        return new OnboardingRecommendationRow(
                row.covenantId(), row.name(), row.matchReason(), row.assessmentType(),
                frequency, cron, row.custom(), row.alreadyApplied(), "EXTRACTED",
                auto ? e.recommendedExpression() : row.conditionOverride(),
                auto ? ThresholdSource.EXTRACTED : row.thresholdSource(),
                row.mlRecommendedExpression(),
                e.recommendedExpression(), e.confidence(), e.source(), null);
    }

    private static String reasonOf(ExtractionRecommendation e) {
        if (e.source() != null && e.source().page() != null) {
            return "From loan agreement, page " + e.source().page();
        }
        return "From loan agreement";
    }

    private static String cronOf(Frequency frequency, CovenantMaster master) {
        return frequency == master.getDefaultFrequency() ? master.getDefaultCron() : frequency.defaultCron();
    }

    /** Maps the C1 frequency enum (plus "ANNUAL") to a schedule; unknown or CUSTOM keeps the fallback. */
    static Frequency frequencyOf(String label, Frequency fallback) {
        if (label == null || label.isBlank()) {
            return fallback;
        }
        String key = label.trim();
        if ("ANNUAL".equals(key) || "ANNUALLY".equals(key)) {
            return Frequency.YEARLY;
        }
        for (Frequency f : Frequency.values()) {
            if (f != Frequency.CUSTOM && f.name().equals(key)) {
                return f;
            }
        }
        return fallback;
    }

    private static String conditionOf(CovenantMaster master) {
        return master != null ? master.getConditionExpression() : null;
    }
}
