package com.crisil.covenant.dto;

import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.enums.AssessmentType;
import java.util.List;

/**
 * Common shape of the create/update covenant payloads so {@code CovenantMasterService} can apply the
 * writable fields once. Implemented by {@link CreateCovenantRequest} and {@link UpdateCovenantRequest}
 * (their record accessors and {@code safe*} helpers satisfy these methods).
 */
public interface CovenantWriteRequest {

    String name();

    CovenantCategory category();

    String description();

    String conditionExpression();

    boolean islamicBanking();

    AssessmentType safeAssessmentType();

    /** US-FREQ-07: recommended monitoring cadence (defaults to Monthly when omitted). */
    Frequency safeDefaultFrequency();

    /** Quartz cron, required only when {@link #safeDefaultFrequency()} is CUSTOM. */
    String defaultCron();

    List<String> safeBusinessSegments();

    List<String> safeIndustries();

    List<String> safeCountriesOfRisk();

    List<String> safeCountriesOfIncorporation();

    List<String> safeCreditProposalTypes();

    List<String> safeLegalEntityTypes();

    List<String> safeFacilityTypes();

    List<String> safeCollateralTypes();
}
