package com.crisil.covenant.service;

import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.exception.DuplicateResourceException;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.entity.ExpressionChangeLog;
import com.crisil.covenant.repository.ExpressionChangeLogRepository;
import com.crisil.covenant.dto.CovenantResponse;
import com.crisil.covenant.dto.CovenantUsageResponse;
import com.crisil.covenant.dto.CovenantWriteRequest;
import com.crisil.covenant.dto.CreateCovenantRequest;
import com.crisil.covenant.dto.UpdateCovenantRequest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.quartz.CronExpression;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crisil.covenant.domain.Eligibility;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.util.CovenantMasterSpecifications;

@Service
@Transactional
public class CovenantMasterService {

    private final CovenantMasterRepository repository;
    private final CovenantConditionValidator conditionValidator;
    private final ExpressionChangeLogRepository changeLogRepository;
    private final AppliedCovenantRepository appliedRepository;

    public CovenantMasterService(CovenantMasterRepository repository,
                                 CovenantConditionValidator conditionValidator,
                                 ExpressionChangeLogRepository changeLogRepository,
                                 AppliedCovenantRepository appliedRepository) {
        this.repository = repository;
        this.conditionValidator = conditionValidator;
        this.changeLogRepository = changeLogRepository;
        this.appliedRepository = appliedRepository;
    }

    @Transactional(readOnly = true)
    public CovenantUsageResponse usage(String id) {
        findOrThrow(id);
        long total = appliedRepository.countByCovenantMasterId(id);
        long active = appliedRepository.countByCovenantMasterIdAndStatus(id, AppliedCovenantStatus.ACTIVE);
        List<String> cpIds = appliedRepository.findCpIdsByCovenantMasterId(id);
        List<String> sample = cpIds.size() > 25 ? cpIds.subList(0, 25) : cpIds;
        return new CovenantUsageResponse(id, total, active, sample);
    }

    public CovenantResponse create(CreateCovenantRequest request) {
        if (repository.existsByNameIgnoreCase(request.name())) {
            throw new DuplicateResourceException(
                    "Covenant with name '" + request.name() + "' already exists");
        }
        // US-CM-03: only AUTO covenants carry a measure formula that is validated.
        if (request.safeAssessmentType() == AssessmentType.AUTO) {
            conditionValidator.validateOrThrow(request.conditionExpression());
        }

        CovenantMaster entity = new CovenantMaster();
        applyWritableFields(entity, request);
        entity.setActive(true);
        return CovenantResponse.from(repository.saveAndFlush(entity));
    }

    public CovenantResponse update(String id, UpdateCovenantRequest request) {
        CovenantMaster entity = findOrThrow(id);
        String existingNormalised = entity.getName().toLowerCase(Locale.ROOT);
        String incomingNormalised = request.name().toLowerCase(Locale.ROOT);
        if (!existingNormalised.equals(incomingNormalised)
                && repository.existsByNameIgnoreCase(request.name())) {
            throw new DuplicateResourceException(
                    "Covenant with name '" + request.name() + "' already exists");
        }
        if (request.safeAssessmentType() == AssessmentType.AUTO) {
            conditionValidator.validateOrThrow(request.conditionExpression());
        }

        String changedBy = CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
        String oldFormula = Objects.toString(entity.getConditionExpression(), "");
        String oldEligibility = eligibilitySignature(entity);

        String newFormula = request.conditionExpression();
        if (!oldFormula.equals(newFormula)) {
            changeLogRepository.save(new ExpressionChangeLog(
                    entity.getId(), oldFormula, newFormula, Instant.now(), changedBy));
        }

        applyWritableFields(entity, request);

        // US-CM-01 AC6: eligibility/classification changes are audited (old -> new, who, when).
        String newEligibility = eligibilitySignature(entity);
        if (!oldEligibility.equals(newEligibility)) {
            changeLogRepository.save(new ExpressionChangeLog(
                    entity.getId(), truncate(oldEligibility), truncate(newEligibility),
                    Instant.now(), changedBy));
        }
        return CovenantResponse.from(repository.saveAndFlush(entity));
    }

    /** Apply the create/update payload to the entity, normalising every multi-select wildcard. */
    private void applyWritableFields(CovenantMaster entity, CovenantWriteRequest request) {
        entity.setName(request.name());
        entity.setCategory(request.category());
        entity.setDescription(request.description());
        entity.setConditionExpression(request.conditionExpression());
        entity.setIslamicBanking(request.islamicBanking());
        entity.setAssessmentType(request.safeAssessmentType());
        entity.setDefaultFrequency(request.safeDefaultFrequency());
        entity.setDefaultCron(validatedDefaultCron(request));
        entity.setPolicyReference(request.policyReference() == null || request.policyReference().isBlank()
                ? null : request.policyReference().trim());
        entity.setBusinessSegments(Eligibility.normalize(request.safeBusinessSegments()));
        entity.setIndustries(Eligibility.normalize(request.safeIndustries()));
        entity.setCountriesOfRisk(Eligibility.normalize(request.safeCountriesOfRisk()));
        entity.setCountriesOfIncorporation(Eligibility.normalize(request.safeCountriesOfIncorporation()));
        entity.setCreditProposalTypes(Eligibility.normalize(request.safeCreditProposalTypes()));
        entity.setLegalEntityTypes(Eligibility.normalize(request.safeLegalEntityTypes()));
        entity.setFacilityTypes(Eligibility.normalize(request.safeFacilityTypes()));
        entity.setCollateralTypes(Eligibility.normalize(request.safeCollateralTypes()));
        // US-CM-03 / US-ASM-05: MANUAL covenants reference no measures (the text is not a formula).
        if (request.safeAssessmentType() == AssessmentType.AUTO) {
            entity.setReferencedMeasures(
                    conditionValidator.referencedMeasures(request.conditionExpression()));
        } else {
            entity.setReferencedMeasures(List.of());
        }
    }

    /**
     * A stable, human-readable snapshot of the audited eligibility/classification fields. Lists are
     * normalised so a semantic no-op (e.g. a legacy empty list vs the {@code ["ALL"]} wildcard) is
     * not logged as a change.
     */
    private static String eligibilitySignature(CovenantMaster e) {
        return "islamicBanking=" + (e.isIslamicBanking() ? "Yes" : "No")
                + "; assessmentType=" + e.getAssessmentType()
                + "; segment=" + Eligibility.normalize(e.getBusinessSegments())
                + "; industry=" + Eligibility.normalize(e.getIndustries())
                + "; countryOfRisk=" + Eligibility.normalize(e.getCountriesOfRisk())
                + "; countryOfIncorporation=" + Eligibility.normalize(e.getCountriesOfIncorporation())
                + "; creditProposalType=" + Eligibility.normalize(e.getCreditProposalTypes())
                + "; legalEntityType=" + Eligibility.normalize(e.getLegalEntityTypes())
                + "; facilityType=" + Eligibility.normalize(e.getFacilityTypes())
                + "; collateralType=" + Eligibility.normalize(e.getCollateralTypes());
    }

    private static String truncate(String value) {
        return value.substring(0, Math.min(2000, value.length()));
    }

    public CovenantResponse setActive(String id, boolean active) {
        CovenantMaster entity = findOrThrow(id);
        entity.setActive(active);
        return CovenantResponse.from(repository.saveAndFlush(entity));
    }

    @Transactional(readOnly = true)
    public CovenantResponse get(String id) {
        return CovenantResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<CovenantResponse> search(
            String search,
            CovenantCategory category,
            String businessSegment,
            Boolean active,
            Pageable pageable) {
        Specification<CovenantMaster> spec = CovenantMasterSpecifications.matching(
                search, category, businessSegment, active);
        return repository.findAll(spec, pageable).map(CovenantResponse::from);
    }

    @Transactional(readOnly = true)
    public List<ExpressionChangeLog> auditTrail(String id) {
        findOrThrow(id);
        return changeLogRepository.findByCovenantIdOrderByChangedDateDesc(id);
    }

    /** US-FREQ-07 AC5: a CUSTOM default frequency requires a valid Quartz cron; others derive it. */
    private static String validatedDefaultCron(CovenantWriteRequest request) {
        if (request.safeDefaultFrequency() != Frequency.CUSTOM) {
            return null;
        }
        String cron = request.defaultCron();
        if (cron == null || cron.isBlank() || !CronExpression.isValidExpression(cron)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRON",
                    "Custom default frequency requires a valid Quartz cron expression");
        }
        return cron.trim();
    }

    private CovenantMaster findOrThrow(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Covenant " + id + " not found"));
    }
}
