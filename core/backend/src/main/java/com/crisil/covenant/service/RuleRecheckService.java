package com.crisil.covenant.service;

import com.crisil.covenant.dto.RuleRecheck;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentTrigger;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * After an author changes a library rule, check every CP that follows it again at once, so no CP page
 * shows the new rule next to a result decided on the old one. A CP with its own threshold is left
 * alone: its rule did not change. The checks run in the background (a large book takes minutes), each
 * as its own assessment row (trigger MANUAL, by the author), and one failing check does not stop the
 * others.
 */
@Service
public class RuleRecheckService {

    private static final Logger LOG = LoggerFactory.getLogger(RuleRecheckService.class);

    private final AppliedCovenantRepository appliedRepo;
    private final AssessmentService assessmentService;
    private final TaskExecutor executor;

    public RuleRecheckService(AppliedCovenantRepository appliedRepo, AssessmentService assessmentService,
                              @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.appliedRepo = appliedRepo;
        this.assessmentService = assessmentService;
        this.executor = executor;
    }

    /** Queues the checks and returns how many there are; results appear on each CP as they finish. */
    public RuleRecheck start(String covenantMasterId, String user) {
        List<String> ids = following(covenantMasterId);
        if (!ids.isEmpty()) {
            executor.execute(() -> run(ids, user));
        }
        return new RuleRecheck(ids.size());
    }

    /** Active CP covenants that use the library rule as it is (no threshold of their own). */
    List<String> following(String covenantMasterId) {
        return appliedRepo.findByCovenantMasterIdAndStatusOrderByIdAsc(covenantMasterId, AppliedCovenantStatus.ACTIVE)
                .stream()
                .filter(ac -> ac.getConditionExpression() == null || ac.getConditionExpression().isBlank())
                .map(AppliedCovenant::getId)
                .toList();
    }

    /** Checks each one; returns {checked, breached, errors} for the log. */
    int[] run(List<String> ids, String user) {
        int breached = 0;
        int errors = 0;
        for (String id : ids) {
            try {
                Assessment a = assessmentService.runAssessment(id, AssessmentTrigger.MANUAL, user);
                if (a.getResult() == AssessmentResult.BREACHED) {
                    breached++;
                } else if (a.getResult() == AssessmentResult.ERROR) {
                    errors++;
                }
            } catch (RuntimeException ex) {
                errors++;
                String safe = (id + ": " + ex.getMessage()).replace('\n', '_').replace('\r', '_');
                LOG.warn("Re-check after a rule change failed for {}", safe);
            }
        }
        LOG.info("Re-check after a rule change: {} checked, {} breached, {} could not be checked",
                ids.size(), breached, errors);
        return new int[] {ids.size(), breached, errors};
    }
}
