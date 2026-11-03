package com.crisil.covenant.service;

import com.crisil.covenant.dto.AppliedCovenantResponse;
import com.crisil.covenant.dto.ApplyCovenantRequest;
import com.crisil.covenant.dto.BulkApplyCovenantsRequest;
import com.crisil.covenant.dto.DefinitionChangeRow;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.entity.AppliedCovenantAudit;
import com.crisil.covenant.repository.AppliedCovenantAuditRepository;
import com.crisil.covenant.entity.ThresholdAudit;
import com.crisil.covenant.repository.ThresholdAuditRepository;
import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.entity.CovenantRecommendation;
import com.crisil.covenant.repository.CovenantRecommendationRepository;
import com.crisil.covenant.enums.RecommendationStatus;
import com.crisil.covenant.service.ScheduleService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.quartz.CronExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.ThresholdSource;
import com.crisil.covenant.repository.AppliedCovenantRepository;

@Service
@Transactional
public class AppliedCovenantService {

    private final AppliedCovenantRepository appliedRepo;
    private final CovenantMasterRepository masterRepo;
    private final ScheduleService scheduleService;
    private final CovenantRecommendationRepository recommendationRepo;
    private final AppliedCovenantAuditRepository auditRepo;
    private final ThresholdAuditRepository thresholdAuditRepo;
    private final CovenantConditionValidator conditionValidator;

    public AppliedCovenantService(AppliedCovenantRepository appliedRepo,
                                  CovenantMasterRepository masterRepo,
                                  ScheduleService scheduleService,
                                  CovenantRecommendationRepository recommendationRepo,
                                  AppliedCovenantAuditRepository auditRepo,
                                  ThresholdAuditRepository thresholdAuditRepo,
                                  CovenantConditionValidator conditionValidator) {
        this.appliedRepo = appliedRepo;
        this.masterRepo = masterRepo;
        this.scheduleService = scheduleService;
        this.recommendationRepo = recommendationRepo;
        this.auditRepo = auditRepo;
        this.thresholdAuditRepo = thresholdAuditRepo;
        this.conditionValidator = conditionValidator;
    }

    public List<AppliedCovenantResponse> applyBulk(String cpId, BulkApplyCovenantsRequest request) {
        List<AppliedCovenantResponse> out = new ArrayList<>();
        Set<String> appliedIds = new HashSet<>();
        for (ApplyCovenantRequest item : request.items()) {
            appliedIds.add(item.covenantMasterId());
            // Onboarding is idempotent: a covenant already applied to this CP is skipped rather than
            // failing the whole batch — recommendations can include covenants already on the CP, and
            // a duplicate must not block the other (new) covenants from being applied.
            if (appliedRepo.existsByCpIdAndCovenantMasterId(cpId, item.covenantMasterId())) {
                continue;
            }
            out.add(applyOne(cpId, item));
        }
        reconcileRecommendationStatuses(cpId, appliedIds);
        return out;
    }

    private void reconcileRecommendationStatuses(String cpId, Set<String> appliedIds) {
        List<CovenantRecommendation> existing = recommendationRepo.findByCpId(cpId);
        Set<String> existingIds = new HashSet<>();
        for (CovenantRecommendation rec : existing) {
            existingIds.add(rec.getCovenantMasterId());
            if (appliedIds.contains(rec.getCovenantMasterId())) {
                rec.setStatus(RecommendationStatus.ACCEPTED);
            } else if (rec.getStatus() == RecommendationStatus.SUGGESTED) {
                rec.setStatus(RecommendationStatus.REJECTED);
            }
            recommendationRepo.save(rec);
        }
        // Anything applied that wasn't previously suggested → manual add.
        for (String id : appliedIds) {
            if (!existingIds.contains(id)) {
                CovenantRecommendation manual = new CovenantRecommendation();
                manual.setCpId(cpId);
                manual.setCovenantMasterId(id);
                manual.setMatchReason("Added manually");
                manual.setStatus(RecommendationStatus.MANUAL_ADD);
                recommendationRepo.save(manual);
            }
        }
    }

    private AppliedCovenantResponse applyOne(String cpId, ApplyCovenantRequest item) {
        // Callers (applyBulk) skip covenants already applied to the CP, so this only runs for new ones.
        CovenantMaster master = masterRepo.findById(item.covenantMasterId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Covenant " + item.covenantMasterId() + " not found"));
        if (!master.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INACTIVE_COVENANT",
                    "Cannot apply inactive covenant " + master.getId());
        }
        String cron = resolveCron(item);

        AppliedCovenant entity = new AppliedCovenant();
        entity.setCpId(cpId);
        entity.setCovenantMaster(master);
        entity.setFrequency(item.frequency());
        entity.setCronExpression(cron);
        entity.setStartDate(resolveStartDate(item));
        entity.setStatus(AppliedCovenantStatus.ACTIVE);
        entity.setSnapshotJson("{}");
        String override = validatedOverride(item.conditionExpression());
        entity.setConditionExpression(override);
        entity.setConditionType(item.conditionType());
        entity.setClassification(item.classification());
        ThresholdSource source = resolveThresholdSource(item, override);
        entity.setThresholdSource(source);
        AppliedCovenant saved = appliedRepo.saveAndFlush(entity);
        scheduleService.createSchedule(saved);
        // US-ML-13 AC4 / US-CM-25 AC6: log the applied decision whenever a per-CP definition was
        // committed — master expression → per-CP expression, with the resolved provenance.
        if (override != null) {
            String changedBy = CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
            thresholdAuditRepo.save(new ThresholdAudit(
                    cpId, master.getId(), saved.getId(),
                    source, ThresholdAudit.Event.APPLIED,
                    master.getConditionExpression(), override, null, null,
                    changedBy, Instant.now()));
        }
        return AppliedCovenantResponse.from(saved);
    }

    /**
     * Resolve the persisted provenance: {@code MASTER} when no override is committed, otherwise the
     * request's source (a null or {@code MASTER} request with an override is treated as {@code USER}).
     */
    private static ThresholdSource resolveThresholdSource(ApplyCovenantRequest item, String override) {
        if (override == null) {
            return ThresholdSource.MASTER;
        }
        ThresholdSource requested = item.thresholdSource();
        return requested == null || requested == ThresholdSource.MASTER
                ? ThresholdSource.USER
                : requested;
    }

    /**
     * Validate a per-CP condition; null/blank → no override (use the master's).
     *
     * <p>US-CM-25: the whole definition is editable per CP, so any valid boolean measure formula is
     * accepted whatever measures it uses — it need not reference the same measures as the master.
     * Every runtime consumer resolves the per-CP expression else the master's and compiles that, so a
     * definition using measures the master never did is still bound, pre-staged and evaluated.
     */
    private String validatedOverride(String override) {
        if (override == null || override.isBlank()) {
            return null;
        }
        conditionValidator.validateOrThrow(override);
        return override.trim();
    }

    /** US-FREQ-07 AC3: monitoring start date — defaults to today, must be today or later. */
    static LocalDate resolveStartDate(ApplyCovenantRequest item) {
        LocalDate startDate = item.startDate() != null ? item.startDate() : LocalDate.now();
        if (startDate.isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_START_DATE",
                    "Monitoring start date cannot be in the past");
        }
        return startDate;
    }

    static String resolveCron(ApplyCovenantRequest item) {
        if (item.frequency() == Frequency.CUSTOM) {
            String cron = item.cronExpression();
            if (cron == null || cron.isBlank() || !CronExpression.isValidExpression(cron)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRON",
                        "Custom frequency requires a valid Quartz cron expression");
            }
            return cron;
        }
        return item.frequency().defaultCron();
    }

    @Transactional(readOnly = true)
    public List<AppliedCovenantResponse> listForCp(String cpId) {
        return appliedRepo.findByCpIdOrderByIdAsc(cpId).stream()
                .map(AppliedCovenantResponse::from)
                .toList();
    }

    public AppliedCovenantResponse update(String id,
            com.crisil.covenant.dto.UpdateAppliedCovenantRequest request) {
        AppliedCovenant entity = appliedRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Applied covenant " + id + " not found"));

        String changedBy = CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
        Instant now = Instant.now();
        if (request.frequency() != null && request.frequency() != entity.getFrequency()) {
            String newCron = resolveCron(new com.crisil.covenant.dto.ApplyCovenantRequest(
                    entity.getCovenantMaster().getId(),
                    request.frequency(),
                    request.cronExpression(),
                    null));
            auditRepo.save(new AppliedCovenantAudit(
                    id, AppliedCovenantAudit.ChangeType.FREQUENCY,
                    entity.getFrequency().name(), request.frequency().name(),
                    now, changedBy));
            if (!newCron.equals(entity.getCronExpression())) {
                auditRepo.save(new AppliedCovenantAudit(
                        id, AppliedCovenantAudit.ChangeType.CRON,
                        entity.getCronExpression(), newCron, now, changedBy));
            }
            entity.setFrequency(request.frequency());
            entity.setCronExpression(newCron);
            scheduleService.findByApplied(id).ifPresent(s -> scheduleService.reschedule(s, newCron));
        }
        if (request.conditionExpression() != null) {
            amendCondition(entity, request.conditionExpression(), request.reason(), changedBy, now);
        }
        if (request.status() != null && request.status() != entity.getStatus()) {
            auditRepo.save(new AppliedCovenantAudit(
                    id, AppliedCovenantAudit.ChangeType.STATUS,
                    entity.getStatus().name(), request.status().name(),
                    now, changedBy));
            entity.setStatus(request.status());
            switch (request.status()) {
                case SUSPENDED -> scheduleService.findByApplied(id).ifPresent(scheduleService::pause);
                case ACTIVE -> scheduleService.findByApplied(id).ifPresent(scheduleService::resume);
                case EXPIRED -> { /* no schedule action */ }
            }
        }
        return AppliedCovenantResponse.from(appliedRepo.saveAndFlush(entity));
    }

    /**
     * Replace this CP's rule for the covenant, for example after a loan amendment, and log it to the
     * definition history. A blank expression goes back to the library rule. Earlier results keep the
     * rule they were checked against; the new one applies from the next check.
     */
    private void amendCondition(AppliedCovenant entity, String requested, String reason,
                                String changedBy, Instant now) {
        String override = validatedOverride(requested);
        String master = entity.getCovenantMaster().getConditionExpression();
        String before = entity.getConditionExpression() != null ? entity.getConditionExpression() : master;
        String after = override != null ? override : master;
        if (Objects.equals(before, after) && Objects.equals(override, entity.getConditionExpression())) {
            return;
        }
        ThresholdSource source = override == null ? ThresholdSource.MASTER : ThresholdSource.USER;
        entity.setConditionExpression(override);
        entity.setThresholdSource(source);
        thresholdAuditRepo.save(new ThresholdAudit(
                entity.getCpId(), entity.getCovenantMaster().getId(), entity.getId(),
                source, ThresholdAudit.Event.AMENDED, before, after, null,
                reason == null || reason.isBlank() ? null : reason.trim(), changedBy, now));
    }

    @Transactional(readOnly = true)
    public List<AppliedCovenantAudit> auditFor(String appliedCovenantId) {
        return auditRepo.findByAppliedCovenantIdOrderByChangedDateDesc(appliedCovenantId);
    }

    /**
     * US-CM-25 AC7: the per-CP definition change log for one applied covenant, newest first. Empty
     * when the covenant was applied without an override.
     */
    @Transactional(readOnly = true)
    public List<DefinitionChangeRow> definitionHistory(String appliedCovenantId) {
        return thresholdAuditRepo.findByAppliedCovenantIdOrderByChangedDateDesc(appliedCovenantId)
                .stream()
                .map(DefinitionChangeRow::from)
                .toList();
    }
}
