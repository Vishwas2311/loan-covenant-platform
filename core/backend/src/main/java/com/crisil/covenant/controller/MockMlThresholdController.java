package com.crisil.covenant.controller;

import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaThreshold;
import com.crisil.covenant.formula.FormulaThresholdEdit;
import com.crisil.covenant.formula.FormulaType;
import com.crisil.covenant.dto.MlCovenantContext;
import com.crisil.covenant.dto.MlThresholdRecommendation;
import com.crisil.covenant.dto.MlThresholdRequest;
import com.crisil.covenant.repository.CovenantMasterRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only in-app stand-in for the external ML threshold service ({@code dev} profile, and the
 * {@code local-stack} profile used by deploy/docker-compose.yml). It
 * produces deterministic, request-aware recommendations by reusing {@link FormulaEngine}: each
 * numeric threshold is nudged (tighten a {@code >}/{@code >=} floor by +5%, loosen a
 * {@code <}/{@code <=} bound by −5%), while BOOLEAN/DATE/STRING thresholds are left unchanged. Because
 * it speaks the exact same DSL as the UI's ThresholdEditor, the recommendations always round-trip.
 *
 * <p>In dev the {@code mlWebClient} base URL points here, so the full resilient HTTP path (time
 * limiter + circuit breaker + retry) runs against this stub on the same process.
 */
@RestController
@RequestMapping("/internal/mock-ml")
@Profile({"dev", "local-stack"})
@Tag(name = "Dev Mocks (internal)",
        description = "Dev-only in-app stand-in for the external ML threshold service. Active under the "
                + "'dev' profile only; the mlWebClient base URL points here so the full resilient HTTP "
                + "path (time limiter + circuit breaker + retry) exercises this stub in-process.")
public class MockMlThresholdController {

    private static final BigDecimal TIGHTEN = new BigDecimal("1.05");
    private static final BigDecimal LOOSEN = new BigDecimal("0.95");
    /** How many library covenants (not already in play) the stub may suggest adding (US-ML-20). */
    private static final int MAX_NEW_SUGGESTIONS = 2;

    private final FormulaEngine formulaEngine;
    private final CovenantMasterRepository masterRepo;

    public MockMlThresholdController(FormulaEngine formulaEngine,
                                     CovenantMasterRepository masterRepo) {
        this.formulaEngine = formulaEngine;
        this.masterRepo = masterRepo;
    }

    @PostMapping("/threshold-recommendations")
    @Operation(summary = "Dev stub: deterministic ML threshold recommendations for the given covenants",
            description = "Reuses the FormulaEngine to produce request-aware, deterministic "
                    + "recommendations: numeric floors (>,>=) are tightened by +5%, numeric bounds "
                    + "(<,<=) loosened by -5%, and BOOLEAN/DATE/STRING thresholds are left unchanged. "
                    + "Confidence is a stable pseudo-value derived from the covenant id. Not for "
                    + "production use.")
    public List<MlThresholdRecommendation> recommend(@RequestBody MlThresholdRequest request) {
        List<MlCovenantContext> covenants =
                request.covenants() == null ? List.of() : request.covenants();
        List<MlThresholdRecommendation> out = new ArrayList<>();
        Set<String> inPlay = new HashSet<>();
        for (MlCovenantContext covenant : covenants) {
            inPlay.add(covenant.covenantMasterId());
            out.add(recommendOne(covenant));
        }
        // Already-applied covenants are context only — never re-suggested.
        if (request.appliedContext() != null) {
            for (MlCovenantContext applied : request.appliedContext()) {
                inPlay.add(applied.covenantMasterId());
            }
        }
        out.addAll(newCovenantSuggestions(inPlay));
        return out;
    }

    /**
     * US-ML-20 (dev only): suggest activating up to {@link #MAX_NEW_SUGGESTIONS} shared library
     * covenants the master (eligibility) stage did not surface and that are not already in play. AUTO
     * covenants get their master threshold nudged; MANUAL covenants are suggested as-is (no threshold).
     * Deterministic — masters are taken in stable id order.
     */
    private List<MlThresholdRecommendation> newCovenantSuggestions(Set<String> inPlay) {
        List<MlThresholdRecommendation> added = new ArrayList<>();
        List<CovenantMaster> candidates = masterRepo.findAll().stream()
                .filter(CovenantMaster::isActive)
                .filter(m -> !m.isCustom())
                .filter(m -> !inPlay.contains(m.getId()))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
        boolean addedManual = false;
        boolean addedAuto = false;
        for (CovenantMaster master : candidates) {
            if (added.size() >= MAX_NEW_SUGGESTIONS) {
                break;
            }
            boolean manual = master.getAssessmentType() == AssessmentType.MANUAL;
            // Prefer variety: one AUTO + one MANUAL when both are available.
            if (manual && addedManual) {
                continue;
            }
            if (!manual && addedAuto) {
                continue;
            }
            added.add(suggestNew(master));
            addedManual = addedManual || manual;
            addedAuto = addedAuto || !manual;
        }
        return added;
    }

    private MlThresholdRecommendation suggestNew(CovenantMaster master) {
        String expression = master.getConditionExpression();
        if (master.getAssessmentType() == AssessmentType.MANUAL) {
            return new MlThresholdRecommendation(master.getId(), expression,
                    confidenceFor(master.getId()),
                    "Peer CPs in this profile monitor this covenant; recommend adding it.");
        }
        List<FormulaThresholdEdit> edits = new ArrayList<>();
        for (FormulaThreshold slot : formulaEngine.thresholds(expression)) {
            nudge(slot).ifPresent(edits::add);
        }
        String recommended = edits.isEmpty() ? expression : formulaEngine.applyThresholds(expression, edits);
        return new MlThresholdRecommendation(master.getId(), recommended, confidenceFor(master.getId()),
                "Recommend adding this covenant with a threshold calibrated to the CP profile.");
    }

    private MlThresholdRecommendation recommendOne(MlCovenantContext covenant) {
        String current = covenant.currentExpression();
        List<FormulaThresholdEdit> edits = new ArrayList<>();
        for (FormulaThreshold slot : formulaEngine.thresholds(current)) {
            nudge(slot).ifPresent(edits::add);
        }
        String recommended = edits.isEmpty() ? current : formulaEngine.applyThresholds(current, edits);
        String rationale = edits.isEmpty()
                ? "No numeric thresholds to tune; covenant left unchanged."
                : "Adjusted " + edits.size() + " threshold(s) from the CP profile and peer benchmarks.";
        return new MlThresholdRecommendation(
                covenant.covenantMasterId(), recommended, confidenceFor(covenant.covenantMasterId()),
                rationale);
    }

    /** Deterministic nudge of one threshold slot; empty when the slot must be left unchanged. */
    private static Optional<FormulaThresholdEdit> nudge(FormulaThreshold slot) {
        if (slot.valueType() != FormulaType.NUMBER) {
            return Optional.empty();
        }
        BigDecimal factor = switch (slot.operator()) {
            case ">", ">=" -> TIGHTEN;
            case "<", "<=" -> LOOSEN;
            default -> null;
        };
        if (factor == null) {
            return Optional.empty();
        }
        String display = slot.displayValue();
        boolean percent = display.endsWith("%");
        String numeric = percent ? display.substring(0, display.length() - 1) : display;
        BigDecimal value;
        try {
            value = new BigDecimal(numeric.trim());
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        BigDecimal nudged = value.multiply(factor);
        if (nudged.scale() > 4) {
            nudged = nudged.setScale(4, RoundingMode.HALF_UP);
        }
        String newValue = nudged.stripTrailingZeros().toPlainString() + (percent ? "%" : "");
        return Optional.of(new FormulaThresholdEdit(
                slot.startOffset(), slot.endOffset(), FormulaType.NUMBER, newValue));
    }

    /** Deterministic pseudo-confidence in [0.80, 0.99], derived from the covenant id. */
    private static BigDecimal confidenceFor(String covenantMasterId) {
        long id = covenantMasterId == null ? 0L : covenantMasterId.hashCode();
        return new BigDecimal("0.80")
                .add(BigDecimal.valueOf(Math.floorMod(id, 20L)).multiply(new BigDecimal("0.01")))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
