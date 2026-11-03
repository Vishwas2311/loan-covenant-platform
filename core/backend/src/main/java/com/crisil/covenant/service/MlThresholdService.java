package com.crisil.covenant.service;

import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.entity.ThresholdAudit;
import com.crisil.covenant.repository.ThresholdAuditRepository;
import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.service.CachedCovenantAttributesService;
import com.crisil.covenant.domain.CovenantAttributes;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.service.MeasureCatalogService;
import com.crisil.covenant.dto.MlCovenantContext;
import com.crisil.covenant.dto.MlThresholdRecommendation;
import com.crisil.covenant.dto.MlThresholdRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates ML threshold recommendations for a CP (US-ML-12 / US-ML-13 / US-ML-20):
 * <ol>
 *   <li>shares only the CP's details (attributes + segment/sector) with the ML service — never the
 *       covenant list being onboarded — so the model reasons purely from the CP profile;</li>
 *   <li>calls the resilient {@link MlThresholdRecommender} (empty list when ML is unavailable);</li>
 *   <li>writes an append-only {@code RECOMMENDED} audit row for <em>every</em> returned recommendation
 *       — even ones later declined or ignored;</li>
 *   <li>returns only the recommendations that actually differ from the current threshold and are
 *       valid formulas over the same measures, so the UI never sees a no-op or an invalid rule.</li>
 * </ol>
 */
@Service
@Transactional
public class MlThresholdService {

    private final AppliedCovenantRepository appliedRepo;
    private final CovenantMasterRepository masterRepo;
    private final CachedCovenantAttributesService attributesService;
    private final MlThresholdRecommender recommender;
    private final ThresholdAuditRepository auditRepo;
    private final FormulaEngine formulaEngine;
    private final MeasureCatalogService measureCatalog;

    public MlThresholdService(AppliedCovenantRepository appliedRepo,
                              CovenantMasterRepository masterRepo,
                              CachedCovenantAttributesService attributesService,
                              MlThresholdRecommender recommender,
                              ThresholdAuditRepository auditRepo,
                              FormulaEngine formulaEngine,
                              MeasureCatalogService measureCatalog) {
        this.appliedRepo = appliedRepo;
        this.masterRepo = masterRepo;
        this.attributesService = attributesService;
        this.recommender = recommender;
        this.auditRepo = auditRepo;
        this.formulaEngine = formulaEngine;
        this.measureCatalog = measureCatalog;
    }

    public List<MlThresholdRecommendation> recommend(String cpId, List<MlCovenantContext> items) {
        List<MlCovenantContext> scope = items == null ? List.of() : items;
        // Share only the CP payload with the ML service: the model calibrates thresholds and suggests
        // covenants from the CP's profile alone, never from the covenant list being onboarded. The
        // in-scope covenants are retained locally (below) purely to classify each returned
        // recommendation as an override of an in-scope covenant vs a brand-new covenant to add.
        MlThresholdRequest request =
                new MlThresholdRequest(cpId, cpAttributes(cpId), List.of(), List.of());

        List<MlThresholdRecommendation> recommendations = recommender.recommend(request);

        Map<String, String> currentByMaster = new LinkedHashMap<>();
        for (MlCovenantContext item : scope) {
            currentByMaster.put(item.covenantMasterId(), item.currentExpression());
        }

        String changedBy = CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
        Instant now = Instant.now();
        MeasureCatalog catalog = measureCatalog.activeCatalog();

        // US-ML-20: covenants already on this CP can neither be re-tuned nor re-added here.
        Set<String> appliedMasterIds = appliedRepo.findByCpIdOrderByIdAsc(cpId).stream()
                .map(a -> a.getCovenantMaster().getId())
                .collect(java.util.stream.Collectors.toSet());

        List<MlThresholdRecommendation> surfaced = new ArrayList<>();
        for (MlThresholdRecommendation rec : recommendations) {
            if (rec == null || rec.covenantMasterId() == null) {
                continue;
            }
            String id = rec.covenantMasterId();
            if (currentByMaster.containsKey(id)) {
                // Override candidate: an in-scope covenant whose threshold ML may re-tune.
                String current = currentByMaster.get(id);
                auditRecommended(cpId, id, current, rec, changedBy, now);
                if (isChangedAndValid(current, rec.recommendedExpression(), catalog)) {
                    surfaced.add(rec);
                }
            } else if (!appliedMasterIds.contains(id)) {
                // New-covenant candidate: ML may activate an existing library covenant not surfaced by
                // the master (eligibility) stage. Only active masters; unknown/inactive ids are ignored.
                CovenantMaster master = masterRepo.findById(id)
                        .filter(CovenantMaster::isActive)
                        .orElse(null);
                if (master == null) {
                    continue;
                }
                String baseline = master.getConditionExpression();
                auditRecommended(cpId, id, baseline, rec, changedBy, now);
                if (master.getAssessmentType() == AssessmentType.MANUAL) {
                    surfaced.add(rec); // MANUAL covenant: no threshold to validate.
                } else if (isValidSameMeasure(baseline, rec.recommendedExpression(), catalog)) {
                    surfaced.add(rec); // AUTO covenant: recommended must be a valid same-measure rule.
                }
            }
            // else: already applied to this CP → ignore.
        }
        return surfaced;
    }

    /** AC3/US-ML-13: audit every returned recommendation, even declined/unchanged/invalid ones. */
    private void auditRecommended(String cpId, String covenantMasterId, String current,
                                  MlThresholdRecommendation rec, String changedBy, Instant now) {
        auditRepo.save(new ThresholdAudit(
                cpId, covenantMasterId, null,
                ThresholdSource.ML, ThresholdAudit.Event.RECOMMENDED,
                current, rec.recommendedExpression(), rec.confidence(), rec.rationale(),
                changedBy, now));
    }

    private Map<String, String> cpAttributes(String cpId) {
        CovenantAttributes attrs = attributesService.fetch(cpId);
        Map<String, String> map = new LinkedHashMap<>(attrs.safeAttributes());
        if (attrs.businessSegment() != null) {
            map.put("businessSegment", attrs.businessSegment());
        }
        if (attrs.sector() != null) {
            map.put("sector", attrs.sector());
        }
        return map;
    }

    /** True when the recommendation actually changes the threshold and is a valid same-measure rule. */
    private boolean isChangedAndValid(String current, String recommended, MeasureCatalog catalog) {
        if (recommended == null || recommended.isBlank()) {
            return false;
        }
        String currentTrimmed = current == null ? "" : current.trim();
        if (recommended.trim().equals(currentTrimmed)) {
            return false; // AC3: unchanged recommendations are never surfaced
        }
        return isValidSameMeasure(current, recommended, catalog);
    }

    /**
     * True when {@code recommended} is a valid formula over exactly the same measures as
     * {@code baseline} (US-ML-12 AC7). Unlike {@link #isChangedAndValid}, it does not require the value
     * to differ — used for a new AUTO covenant, where activating the covenant is meaningful even if the
     * suggested threshold equals the master default.
     */
    private boolean isValidSameMeasure(String baseline, String recommended, MeasureCatalog catalog) {
        if (recommended == null || recommended.isBlank()) {
            return false; // AC10: a new AUTO covenant needs a concrete rule
        }
        if (!formulaEngine.validate(recommended, catalog, true).valid()) {
            return false; // AC7/AC10: invalid recommendations are never offered
        }
        try {
            Set<String> recMeasures =
                    new HashSet<>(formulaEngine.compile(recommended, catalog).referencedMeasures());
            Set<String> baseMeasures = new HashSet<>(
                    formulaEngine.compile(baseline == null ? "" : baseline.trim(), catalog)
                            .referencedMeasures());
            return recMeasures.equals(baseMeasures);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
