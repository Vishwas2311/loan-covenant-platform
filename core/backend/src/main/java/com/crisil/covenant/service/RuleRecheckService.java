package com.crisil.covenant.service;

import com.crisil.covenant.dto.RuleRecheck;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * After an author changes a library rule, check every CP that follows it again at once, so no CP page
 * shows the new rule next to a result decided on the old one. A CP with its own threshold is left
 * alone: its rule did not change. Each check is its own assessment row (trigger MANUAL, by the author),
 * and one failing check does not stop the others.
 */
@Service
public class RuleRecheckService {

    private static final Logger LOG = LoggerFactory.getLogger(RuleRecheckService.class);

    private final AppliedCovenantRepository appliedRepo;
    private final AssessmentService assessmentService;

    public RuleRecheckService(AppliedCovenantRepository appliedRepo, AssessmentService assessmentService) {
        this.appliedRepo = appliedRepo;
        this.assessmentService = assessmentService;
    }

    public RuleRecheck recheck(String covenantMasterId, String user) {
        int checked = 0;
        int breached = 0;
        int errors = 0;
        for (AppliedCovenant ac : appliedRepo.findByCovenantMasterIdAndStatusOrderByIdAsc(
                covenantMasterId, AppliedCovenantStatus.ACTIVE)) {
            String own = ac.getConditionExpression();
            if (own != null && !own.isBlank()) {
                continue;
            }
            checked++;
            try {
                Assessment a = assessmentService.runAssessment(ac.getId(), AssessmentTrigger.MANUAL, user);
                if (a.getResult() == AssessmentResult.BREACHED) {
                    breached++;
                } else if (a.getResult() == AssessmentResult.ERROR) {
                    errors++;
                }
            } catch (RuntimeException ex) {
                errors++;
                String safe = (ac.getId() + ": " + ex.getMessage()).replace('\n', '_').replace('\r', '_');
                LOG.warn("Re-check after a rule change failed for {}", safe);
            }
        }
        return new RuleRecheck(checked, breached, errors);
    }
}
