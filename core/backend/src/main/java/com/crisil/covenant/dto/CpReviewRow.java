package com.crisil.covenant.dto;

import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.enums.TimingStatus;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.enums.DispositionApproval;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One row of the CP assessment grid (US-MON-16): the effective expression, latest actual value,
 * headroom, derived/dispositioned status, read-only timing and any evidence attachments for a
 * single covenant applied to a CP. Read model only — coverage-excluded ({@code dto/**}).
 *
 * @param dispositionStatus effective status shown in the grid (COMPLIED/BREACHED/WAIVER/EXTENSION)
 * @param dispositionable   whether Status is editable (latest result is BREACHED — US-MON-16 AC12)
 * @param category          FINANCIAL/NON_FINANCIAL — a classification label only
 * @param assessmentType    AUTO/MANUAL; MANUAL hides Actual + Headroom (US-CM-24 AC9). The grid gates
 *                          on this, not on {@code category}: only an AUTO covenant is machine-evaluated,
 *                          so only it can have an Actual to show, whatever its category.
 * @param approvalStatus    FR-12 state of the disposition on the current breach (null when none); a
 *                          REQUESTED or REJECTED one leaves {@code dispositionStatus} at BREACHED
 * @param requestedStatus   the WAIVER/EXTENSION asked for (null when no disposition on this breach)
 * @param requestedBy       who asked for it
 */
public record CpReviewRow(
        String appliedCovenantId,
        String covenantMasterId,
        String covenantName,
        String conditionExpression,
        CovenantCategory category,
        AssessmentType assessmentType,
        String computedValue,
        String computedValueType,
        BigDecimal headroom,
        AssessmentResult latestResult,
        String latestAssessmentId,
        Instant lastAssessedAt,
        CovenantDispositionStatus dispositionStatus,
        LocalDate extensionDate,
        CovenantStatusSource statusSource,
        String note,
        boolean dispositionable,
        LocalDate startDate,
        LocalDate nextDueDate,
        TimingStatus timingStatus,
        long overdueByDays,
        List<AttachmentView> attachments,
        DispositionApproval approvalStatus,
        CovenantDispositionStatus requestedStatus,
        String requestedBy,
        boolean atRisk,
        String errorMessage,
        String assessedCondition) {

    /** Row with no error reason (the latest check did not end in ERROR). */
    public CpReviewRow(String appliedCovenantId, String covenantMasterId, String covenantName,
                       String conditionExpression, CovenantCategory category, AssessmentType assessmentType,
                       String computedValue, String computedValueType, BigDecimal headroom,
                       AssessmentResult latestResult, String latestAssessmentId, Instant lastAssessedAt,
                       CovenantDispositionStatus dispositionStatus, LocalDate extensionDate,
                       CovenantStatusSource statusSource, String note, boolean dispositionable,
                       LocalDate startDate, LocalDate nextDueDate, TimingStatus timingStatus, long overdueByDays,
                       List<AttachmentView> attachments, DispositionApproval approvalStatus,
                       CovenantDispositionStatus requestedStatus, String requestedBy, boolean atRisk) {
        this(appliedCovenantId, covenantMasterId, covenantName, conditionExpression, category, assessmentType,
                computedValue, computedValueType, headroom, latestResult, latestAssessmentId, lastAssessedAt,
                dispositionStatus, extensionDate, statusSource, note, dispositionable, startDate, nextDueDate,
                timingStatus, overdueByDays, attachments, approvalStatus, requestedStatus, requestedBy, atRisk, null, null);
    }

    /** Row without the C3 at-risk flag. */
    public CpReviewRow(String appliedCovenantId, String covenantMasterId, String covenantName,
                       String conditionExpression, CovenantCategory category, AssessmentType assessmentType,
                       String computedValue, String computedValueType, BigDecimal headroom,
                       AssessmentResult latestResult, String latestAssessmentId, Instant lastAssessedAt,
                       CovenantDispositionStatus dispositionStatus, LocalDate extensionDate,
                       CovenantStatusSource statusSource, String note, boolean dispositionable,
                       LocalDate startDate, LocalDate nextDueDate, TimingStatus timingStatus, long overdueByDays,
                       List<AttachmentView> attachments, DispositionApproval approvalStatus,
                       CovenantDispositionStatus requestedStatus, String requestedBy) {
        this(appliedCovenantId, covenantMasterId, covenantName, conditionExpression, category, assessmentType,
                computedValue, computedValueType, headroom, latestResult, latestAssessmentId, lastAssessedAt,
                dispositionStatus, extensionDate, statusSource, note, dispositionable, startDate, nextDueDate,
                timingStatus, overdueByDays, attachments, approvalStatus, requestedStatus, requestedBy, false);
    }

    /** Row without maker-checker data (no disposition on the current breach). */
    public CpReviewRow(String appliedCovenantId, String covenantMasterId, String covenantName,
                       String conditionExpression, CovenantCategory category, AssessmentType assessmentType,
                       String computedValue, String computedValueType, BigDecimal headroom,
                       AssessmentResult latestResult, String latestAssessmentId, Instant lastAssessedAt,
                       CovenantDispositionStatus dispositionStatus, LocalDate extensionDate,
                       CovenantStatusSource statusSource, String note, boolean dispositionable,
                       LocalDate startDate, LocalDate nextDueDate, TimingStatus timingStatus, long overdueByDays,
                       List<AttachmentView> attachments) {
        this(appliedCovenantId, covenantMasterId, covenantName, conditionExpression, category, assessmentType,
                computedValue, computedValueType, headroom, latestResult, latestAssessmentId, lastAssessedAt,
                dispositionStatus, extensionDate, statusSource, note, dispositionable, startDate, nextDueDate,
                timingStatus, overdueByDays, attachments, null, null, null);
    }
}
