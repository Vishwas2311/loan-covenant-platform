package com.crisil.covenant.service;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Collection;
import com.crisil.covenant.util.IdBatches;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaThreshold;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.domain.CovenantOnCp;
import com.crisil.covenant.service.MonitoringService;
import com.crisil.covenant.entity.CovenantStatusAudit;
import com.crisil.covenant.repository.CovenantStatusAuditRepository;
import com.crisil.covenant.dto.AttachmentView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.SetDispositionRequest;
import java.time.Clock;
import com.crisil.covenant.security.Roles;
import com.crisil.covenant.enums.DispositionApproval;
import com.crisil.covenant.dto.DispositionDecisionRequest;
import com.crisil.covenant.dto.StatusAuditView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.crisil.covenant.entity.CovenantAttachment;
import com.crisil.covenant.entity.CovenantDisposition;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.repository.CovenantAttachmentRepository;
import com.crisil.covenant.repository.CovenantDispositionRepository;
import com.crisil.covenant.util.DispositionRules;
import com.crisil.covenant.util.HeadroomCalculator;

/**
 * Backs the CP assessment grid (US-MON-16): builds the per-covenant read model on top of the
 * stored assessment result (status is <em>derived</em>, never recomputed), records RM dispositions
 * (Waiver/Extension) and writes the append-only status-audit trail (US-MON-19). It never mutates
 * {@code cov_assessment}.
 */
@Service
@Transactional(readOnly = true)
public class ReviewService {

    private final MonitoringService monitoringService;
    private final AssessmentRepository assessmentRepo;
    private final CovenantMasterRepository masterRepo;
    private final CovenantDispositionRepository dispositionRepo;
    private final CovenantStatusAuditRepository auditRepo;
    private final CovenantAttachmentRepository attachmentRepo;
    private final FormulaEngine formulaEngine;
    private final ReportingSettingsService settingsService;
    private final Clock clock;

    public ReviewService(MonitoringService monitoringService,
                         AssessmentRepository assessmentRepo,
                         CovenantMasterRepository masterRepo,
                         CovenantDispositionRepository dispositionRepo,
                         CovenantStatusAuditRepository auditRepo,
                         CovenantAttachmentRepository attachmentRepo,
                         FormulaEngine formulaEngine,
                         ReportingSettingsService settingsService,
                         Clock clock) {
        this.monitoringService = monitoringService;
        this.assessmentRepo = assessmentRepo;
        this.masterRepo = masterRepo;
        this.dispositionRepo = dispositionRepo;
        this.auditRepo = auditRepo;
        this.attachmentRepo = attachmentRepo;
        this.formulaEngine = formulaEngine;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /** One grid row per covenant applied to the CP (US-MON-16 AC1). */
    public List<CpReviewRow> reviewRows(String cpId) {
        List<CovenantOnCp> covenants = monitoringService.covenantsForCp(cpId);

        Map<String, CovenantDisposition> dispByAc = new HashMap<>();
        for (CovenantDisposition d : dispositionRepo.findByCpId(cpId)) {
            dispByAc.put(d.getAppliedCovenantId(), d);
        }
        Map<String, CovenantMaster> masterById = new HashMap<>();
        List<String> masterIds = covenants.stream()
                .map(CovenantOnCp::covenantMasterId).filter(Objects::nonNull).distinct().toList();
        for (CovenantMaster m : masterRepo.findAllById(masterIds)) {
            masterById.put(m.getId(), m);
        }
        Map<String, Assessment> assessmentById = new HashMap<>();
        List<String> assessmentIds = covenants.stream()
                .map(CovenantOnCp::latestAssessmentId).filter(Objects::nonNull).distinct().toList();
        for (Assessment a : assessmentRepo.findAllById(assessmentIds)) {
            assessmentById.put(a.getId(), a);
        }
        Map<String, List<AttachmentView>> attByAc = new HashMap<>();
        for (CovenantAttachment a : attachmentRepo.findByCpIdOrderByUploadedAtDesc(cpId)) {
            attByAc.computeIfAbsent(a.getAppliedCovenantId(), k -> new ArrayList<>())
                    .add(AttachmentView.from(a));
        }

        int atRiskPercent = settingsService.atRiskHeadroomPercent();
        List<CpReviewRow> rows = new ArrayList<>(covenants.size());
        for (CovenantOnCp c : covenants) {
            Assessment latest = c.latestAssessmentId() == null ? null
                    : assessmentById.get(c.latestAssessmentId());
            rows.add(toRow(c, dispByAc.get(c.appliedCovenantId()), latest,
                    masterById.get(c.covenantMasterId()),
                    attByAc.getOrDefault(c.appliedCovenantId(), List.of()), atRiskPercent));
        }
        return rows;
    }

    /**
     * {@link #reviewRows(String)} for many CPs at once: the same rows from a few batched queries instead of
     * about eight per CP, keyed by CP id in the order given. For portfolio-wide reports and alerts.
     */
    public Map<String, List<CpReviewRow>> reviewRowsFor(Collection<String> cpIds) {
        return rowsCache.get(List.copyOf(cpIds), () -> Collections.unmodifiableMap(loadRows(cpIds)));
    }

    private final PortfolioCache<List<String>, Map<String, List<CpReviewRow>>> rowsCache =
            new PortfolioCache<>(Duration.ofSeconds(30), 500);

    private Map<String, List<CpReviewRow>> loadRows(Collection<String> cpIds) {
        Map<String, List<CovenantOnCp>> covenantsByCp = monitoringService.covenantsForCps(cpIds);
        List<CovenantOnCp> all = covenantsByCp.values().stream().flatMap(List::stream).toList();
        Map<String, CovenantDisposition> dispByAc = new HashMap<>();
        for (CovenantDisposition d : IdBatches.fetch(cpIds, dispositionRepo::findByCpIdIn)) {
            dispByAc.put(d.getAppliedCovenantId(), d);
        }
        Map<String, CovenantMaster> masterById = new HashMap<>();
        for (CovenantMaster m : IdBatches.fetch(all.stream().map(CovenantOnCp::covenantMasterId)
                .filter(Objects::nonNull).distinct().toList(), masterRepo::findAllById)) {
            masterById.put(m.getId(), m);
        }
        Map<String, Assessment> assessmentById = new HashMap<>();
        for (Assessment a : IdBatches.fetch(all.stream().map(CovenantOnCp::latestAssessmentId)
                .filter(Objects::nonNull).distinct().toList(), assessmentRepo::findAllById)) {
            assessmentById.put(a.getId(), a);
        }
        Map<String, List<AttachmentView>> attByAc = new HashMap<>();
        for (CovenantAttachment a : IdBatches.fetch(cpIds, attachmentRepo::findByCpIdInOrderByUploadedAtDesc)) {
            attByAc.computeIfAbsent(a.getAppliedCovenantId(), k -> new ArrayList<>()).add(AttachmentView.from(a));
        }
        int atRiskPercent = settingsService.atRiskHeadroomPercent();
        Map<String, List<CpReviewRow>> out = new LinkedHashMap<>();
        covenantsByCp.forEach((cp, covenants) -> {
            List<CpReviewRow> rows = new ArrayList<>(covenants.size());
            for (CovenantOnCp c : covenants) {
                rows.add(toRow(c, dispByAc.get(c.appliedCovenantId()),
                        c.latestAssessmentId() == null ? null : assessmentById.get(c.latestAssessmentId()),
                        masterById.get(c.covenantMasterId()),
                        attByAc.getOrDefault(c.appliedCovenantId(), List.of()), atRiskPercent));
            }
            out.put(cp, List.copyOf(rows));
        });
        return out;
    }

    /**
     * Map one applied covenant onto a grid row. {@code master} is nullable — an applied covenant whose
     * master row can no longer be loaded is treated as AUTO, so a value that was genuinely computed is
     * never hidden merely because its classification is unknown.
     */
    private CpReviewRow toRow(CovenantOnCp c, CovenantDisposition disp, Assessment latest,
                              CovenantMaster master, List<AttachmentView> attachments, int atRiskPercent) {
        CovenantCategory category = master == null ? null : master.getCategory();
        AssessmentType assessmentType =
                master == null ? AssessmentType.AUTO : master.getAssessmentType();
        // US-CM-24 AC9: Actual + Headroom follow the ASSESSMENT TYPE, not the category. Only an AUTO
        // covenant is machine-evaluated, so only it can have an Actual; a MANUAL one is verified by a
        // person and never has one. Gating on the category instead (US-MON-16 AC10) silently discarded
        // the computed value of any AUTO covenant classified NON_FINANCIAL — which the covenant library
        // screen lets an author create, since Category and Assessment type are chosen independently.
        boolean manual = assessmentType == AssessmentType.MANUAL;
        String computedValue = (latest == null || manual) ? null : latest.getComputedValue();
        String computedValueType = (latest == null || manual) ? null : latest.getComputedValueType();
        List<FormulaThreshold> thresholds = manual ? List.of() : formulaEngine.thresholds(c.conditionExpression());
        BigDecimal headroom = manual ? null : HeadroomCalculator.compute(
                c.conditionExpression(), thresholds, latest == null ? null : latest.getComputedValue());

        AssessmentResult result = c.latestResult();
        boolean breached = result == AssessmentResult.BREACHED; // AC12: dispositionable only when breached

        CovenantDispositionStatus effectiveStatus;
        CovenantStatusSource source;
        LocalDate extensionDate;
        String note;
        if (DispositionRules.addressesCurrentBreach(disp, c.latestAssessmentId())) {
            effectiveStatus = disp.getStatus();
            source = disp.getStatusSource();
            extensionDate = disp.getExtensionDate();
            note = disp.getNote();
        } else {
            effectiveStatus = breached ? CovenantDispositionStatus.BREACHED : CovenantDispositionStatus.COMPLIED;
            source = CovenantStatusSource.SYSTEM;
            extensionDate = null;
            note = null;
        }

        // FR-12: show the request on the current breach (pending, approved or rejected) next to the status.
        boolean onThisBreach = disp != null && disp.getAssessmentId() != null
                && disp.getAssessmentId().equals(c.latestAssessmentId());
        return new CpReviewRow(
                c.appliedCovenantId(), c.covenantMasterId(), c.covenantName(),
                c.conditionExpression(), category, assessmentType,
                computedValue, computedValueType, headroom,
                result, c.latestAssessmentId(), c.latestExecutedAt(),
                effectiveStatus, extensionDate, source, note, breached,
                c.startDate(), c.nextDueDate(), c.timingStatus(), c.overdueByDays(),
                attachments,
                onThisBreach ? disp.getApprovalStatus() : null,
                onThisBreach ? disp.getStatus() : null,
                onThisBreach ? disp.getRequestedBy() : null,
                result == AssessmentResult.COMPLIANT
                        && HeadroomCalculator.atRisk(headroom, thresholds, atRiskPercent),
                // Why the check could not run (missing figures, a bad rule), so the page can say so.
                result == AssessmentResult.ERROR && latest != null ? latest.getErrorMessage() : null,
                // The rule the latest result was decided on, when the rule has changed since.
                latest != null && latest.getConditionExpression() != null
                        && !latest.getConditionExpression().equals(c.conditionExpression())
                        ? latest.getConditionExpression() : null);
    }

    /**
     * Record a Waiver/Extension disposition on a breached covenant (US-MON-16 AC8/AC9/AC11). With
     * maker-checker on (FR-12, default) it is REQUESTED and changes nothing until an approver decides;
     * with it off it is approved at once and a status-audit row is appended (US-MON-19 AC2/AC3).
     * An extension date must not be in the past. Returns the refreshed grid row.
     */
    @Transactional
    public CpReviewRow setDisposition(String cpId, String appliedId, SetDispositionRequest req) {
        CovenantDispositionStatus status = req.status();
        if (status != CovenantDispositionStatus.WAIVER && status != CovenantDispositionStatus.EXTENSION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only WAIVER or EXTENSION may be recorded on a breach");
        }
        LocalDate extensionDate = status == CovenantDispositionStatus.EXTENSION ? req.extensionDate() : null;
        if (status == CovenantDispositionStatus.EXTENSION && extensionDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An extension date is required for EXTENSION");
        }
        if (extensionDate != null && extensionDate.isBefore(LocalDate.now(clock))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The extension date must be today or later");
        }

        Assessment latest = assessmentRepo
                .findByAppliedCovenantIdOrderByScheduledAtDesc(appliedId, PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
        if (latest == null || !cpId.equals(latest.getAppliedCovenant().getCpId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No assessed covenant " + appliedId + " on CP " + cpId);
        }
        if (latest.getResult() != AssessmentResult.BREACHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A disposition may be recorded only when the latest result is BREACHED");
        }

        CovenantStatusSource source = req.source() == null ? CovenantStatusSource.USER : req.source();
        CovenantDisposition disp = dispositionRepo.findByCpIdAndAppliedCovenantId(cpId, appliedId)
                .orElseGet(CovenantDisposition::new);

        CovenantDispositionStatus oldStatus;
        LocalDate oldExtensionDate;
        if (disp.getId() != null && DispositionRules.addressesCurrentBreach(disp, latest.getId())) {
            oldStatus = disp.getStatus();
            oldExtensionDate = disp.getExtensionDate();
        } else {
            oldStatus = CovenantDispositionStatus.BREACHED; // dispositioning a fresh breach
            oldExtensionDate = null;
        }

        disp.setCpId(cpId);
        disp.setAppliedCovenantId(appliedId);
        disp.setAssessmentId(latest.getId());
        disp.setStatus(status);
        disp.setExtensionDate(extensionDate);
        disp.setStatusSource(source);
        disp.setNote(req.note());
        String changedBy = CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
        boolean approvalRequired = settingsService.dispositionApprovalRequired();
        disp.setRequestedBy(changedBy);
        disp.setDecisionNote(null);
        if (approvalRequired) {
            disp.setApprovalStatus(DispositionApproval.REQUESTED);
            disp.setDecidedBy(null);
            disp.setDecidedAt(null);
        } else {
            disp.setApprovalStatus(DispositionApproval.APPROVED);
            disp.setDecidedBy(changedBy);
            disp.setDecidedAt(Instant.now(clock));
        }
        dispositionRepo.save(disp);

        if (!approvalRequired) {
            auditRepo.save(new CovenantStatusAudit(cpId, appliedId, latest.getId(),
                    oldStatus, status, oldExtensionDate, extensionDate,
                    source, req.note(), changedBy, Instant.now(clock)));
        }
        return rowFor(cpId, appliedId);
    }

    /**
     * FR-12: an APPROVER (or ADMIN), who is not the requester, approves or rejects a REQUESTED
     * disposition on the covenant's current breach. Approval changes the effective status and is
     * written to the status-audit trail; rejection leaves the breach standing.
     */
    @Transactional
    public CpReviewRow decideDisposition(String cpId, String appliedId, DispositionDecisionRequest req) {
        if (!CurrentUserContext.hasAnyRole(Roles.APPROVER, Roles.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an approver may decide a disposition");
        }
        CovenantDisposition disp = dispositionRepo.findByCpIdAndAppliedCovenantId(cpId, appliedId)
                .filter(d -> d.getApprovalStatus() == DispositionApproval.REQUESTED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No disposition awaiting approval for covenant " + appliedId + " on CP " + cpId));
        String latestId = assessmentRepo
                .findByAppliedCovenantIdOrderByScheduledAtDesc(appliedId, PageRequest.of(0, 1))
                .stream().findFirst().map(Assessment::getId).orElse(null);
        if (latestId == null || !latestId.equals(disp.getAssessmentId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The covenant was re-assessed after this request; ask the RM to raise it again");
        }
        String decider = CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
        if (decider.equals(disp.getRequestedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A requester cannot approve their own request");
        }
        boolean approve = req.decision() == DispositionDecisionRequest.Decision.APPROVE;
        disp.setApprovalStatus(approve ? DispositionApproval.APPROVED : DispositionApproval.REJECTED);
        disp.setDecidedBy(decider);
        disp.setDecidedAt(Instant.now(clock));
        disp.setDecisionNote(req.note());
        dispositionRepo.save(disp);
        if (approve) {
            auditRepo.save(new CovenantStatusAudit(cpId, appliedId, latestId,
                    CovenantDispositionStatus.BREACHED, disp.getStatus(), null, disp.getExtensionDate(),
                    disp.getStatusSource(), req.note() != null ? req.note() : disp.getNote(), decider,
                    Instant.now(clock)));
        }
        return rowFor(cpId, appliedId);
    }

    /**
     * Take back a Waiver/Extension request that no approver has decided yet. Only the person who
     * raised it, or an ADMIN, may do so; the breach stands and the RM can raise a new request.
     */
    @Transactional
    public CpReviewRow withdrawDisposition(String cpId, String appliedId, String note) {
        CovenantDisposition disp = dispositionRepo.findByCpIdAndAppliedCovenantId(cpId, appliedId)
                .filter(d -> d.getApprovalStatus() == DispositionApproval.REQUESTED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No disposition awaiting approval for covenant " + appliedId + " on CP " + cpId));
        String user = CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
        if (!user.equals(disp.getRequestedBy()) && !CurrentUserContext.hasAnyRole(Roles.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the requester may withdraw this request");
        }
        disp.setApprovalStatus(DispositionApproval.WITHDRAWN);
        disp.setDecidedBy(user);
        disp.setDecidedAt(Instant.now(clock));
        disp.setDecisionNote(note == null || note.isBlank() ? "Withdrawn by the requester" : note.trim());
        dispositionRepo.save(disp);
        return rowFor(cpId, appliedId);
    }

    private CpReviewRow rowFor(String cpId, String appliedId) {
        return reviewRows(cpId).stream()
                .filter(r -> r.appliedCovenantId().equals(appliedId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Covenant " + appliedId + " not found on CP " + cpId));
    }

    /** Append-only status trail for a CP (US-MON-19 AC4), newest first. */
    public List<StatusAuditView> auditForCp(String cpId) {
        return auditRepo.findByCpIdOrderByChangedDateDesc(cpId).stream().map(StatusAuditView::from).toList();
    }

    /** Append-only status trail for one applied covenant (US-MON-19 AC4), newest first. */
    public List<StatusAuditView> auditForCovenant(String appliedCovenantId) {
        return auditRepo.findByAppliedCovenantIdOrderByChangedDateDesc(appliedCovenantId).stream()
                .map(StatusAuditView::from).toList();
    }
}
