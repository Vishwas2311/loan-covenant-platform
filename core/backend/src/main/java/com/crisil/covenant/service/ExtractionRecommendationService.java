package com.crisil.covenant.service;

import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.dto.ExtractionRecommendation;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.entity.ThresholdAudit;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.repository.ThresholdAuditRepository;
import com.crisil.covenant.config.CurrentUserContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C1 stage of onboarding: asks the Document Processing Service which covenants the CP's loan
 * agreements contain, keeps only those Core can act on, and audits every library recommendation
 * (source {@code EXTRACTED}) whether or not it is surfaced.
 *
 * <p>Kept: an active library covenant not already applied to the CP, whose expression (AUTO) is a
 * valid boolean formula; and custom candidates with a name. A custom candidate keeps its expression
 * only when valid, otherwise it is offered without one so the RM writes the rule.
 */
@Service
@Transactional
public class ExtractionRecommendationService {

    private final ExtractionRecommender recommender;
    private final CovenantMasterRepository masterRepo;
    private final AppliedCovenantRepository appliedRepo;
    private final ThresholdAuditRepository auditRepo;
    private final FormulaEngine formulaEngine;
    private final MeasureCatalogService measureCatalog;

    public ExtractionRecommendationService(ExtractionRecommender recommender,
                                           CovenantMasterRepository masterRepo,
                                           AppliedCovenantRepository appliedRepo,
                                           ThresholdAuditRepository auditRepo,
                                           FormulaEngine formulaEngine,
                                           MeasureCatalogService measureCatalog) {
        this.recommender = recommender;
        this.masterRepo = masterRepo;
        this.appliedRepo = appliedRepo;
        this.auditRepo = auditRepo;
        this.formulaEngine = formulaEngine;
        this.measureCatalog = measureCatalog;
    }

    public List<ExtractionRecommendation> recommend(String cpId) {
        List<ExtractionRecommendation> raw = recommender.recommend(cpId);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> appliedMasterIds = appliedRepo.findByCpIdOrderByIdAsc(cpId).stream()
                .map(a -> a.getCovenantMaster().getId())
                .collect(Collectors.toSet());
        MeasureCatalog catalog = measureCatalog.activeCatalog();
        String changedBy = CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
        Instant now = Instant.now();

        List<ExtractionRecommendation> kept = new ArrayList<>();
        for (ExtractionRecommendation rec : raw) {
            if (rec == null) {
                continue;
            }
            if (rec.covenantMasterId() == null) {
                ExtractionRecommendation custom = keepCustom(rec, catalog);
                if (custom != null) {
                    kept.add(custom);
                }
                continue;
            }
            if (appliedMasterIds.contains(rec.covenantMasterId())) {
                continue;
            }
            CovenantMaster master = masterRepo.findById(rec.covenantMasterId())
                    .filter(CovenantMaster::isActive)
                    .orElse(null);
            if (master == null) {
                continue;
            }
            auditRepo.save(new ThresholdAudit(
                    cpId, master.getId(), null,
                    ThresholdSource.EXTRACTED, ThresholdAudit.Event.RECOMMENDED,
                    master.getConditionExpression(), rec.recommendedExpression(),
                    toDecimal(rec.confidence()), rec.rationale(),
                    changedBy, now));
            if (master.getAssessmentType() == AssessmentType.MANUAL || isValid(rec.recommendedExpression(), catalog)) {
                kept.add(rec);
            }
        }
        return kept;
    }

    private ExtractionRecommendation keepCustom(ExtractionRecommendation rec, MeasureCatalog catalog) {
        ExtractionRecommendation.CustomCovenant custom = rec.customCovenant();
        if (custom == null || custom.name() == null || custom.name().isBlank()) {
            return null;
        }
        if (rec.recommendedExpression() == null || isValid(rec.recommendedExpression(), catalog)) {
            return rec;
        }
        return new ExtractionRecommendation(null, null, rec.confidence(), rec.rationale(),
                rec.frequency(), rec.source(), custom);
    }

    private boolean isValid(String expression, MeasureCatalog catalog) {
        return expression != null && !expression.isBlank()
                && formulaEngine.validate(expression, catalog, true).valid();
    }

    private static BigDecimal toDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
