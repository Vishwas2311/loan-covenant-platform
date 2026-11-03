package com.crisil.covenant.dto;

import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.ThresholdSource;

/**
 * US-ML-20: one fully-merged onboarding grid row, produced server-side by combining the master
 * (eligibility) recommendations with the ML recommendations so the wizard can populate the Review
 * &amp; Configure grid with a single call (no client-side merge, no per-covenant look-ups).
 *
 * <p>{@code source} is {@code RECOMMENDED} for a master row (optionally left untouched by ML) or
 * {@code ML} when ML either overrode a master row's threshold or added a brand-new covenant.
 * {@code conditionOverride} is the effective per-CP expression to seed (null ⇒ use the master's
 * condition), {@code thresholdSource} its provenance, and {@code mlRecommendedExpression} the exact
 * value ML suggested (null when ML did not set one, e.g. a MANUAL covenant).
 *
 * <p>C1 adds rows proposed from the loan agreement: {@code source} {@code EXTRACTED} for a library
 * covenant (the agreement's threshold wins over ML) and {@code EXTRACTED_CUSTOM} for a term with no
 * library match ({@code covenantId == null}; {@code customDefinition} describes it). {@code evidence}
 * cites the agreement page and quote, {@code extractionConfidence} the DPS confidence.
 */
public record OnboardingRecommendationRow(
        String covenantId,
        String name,
        String matchReason,
        AssessmentType assessmentType,
        Frequency defaultFrequency,
        String defaultCron,
        boolean custom,
        boolean alreadyApplied,
        String source,
        String conditionOverride,
        ThresholdSource thresholdSource,
        String mlRecommendedExpression,
        String extractedExpression,
        Double extractionConfidence,
        ExtractionRecommendation.Source evidence,
        String customDefinition) {

    /** Row without extraction data (master and ML rows). */
    public OnboardingRecommendationRow(String covenantId, String name, String matchReason,
                                       AssessmentType assessmentType, Frequency defaultFrequency,
                                       String defaultCron, boolean custom, boolean alreadyApplied,
                                       String source, String conditionOverride,
                                       ThresholdSource thresholdSource, String mlRecommendedExpression) {
        this(covenantId, name, matchReason, assessmentType, defaultFrequency, defaultCron, custom,
                alreadyApplied, source, conditionOverride, thresholdSource, mlRecommendedExpression,
                null, null, null, null);
    }
}
