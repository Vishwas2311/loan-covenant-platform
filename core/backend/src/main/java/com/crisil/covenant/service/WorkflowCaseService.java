package com.crisil.covenant.service;

import com.crisil.covenant.domain.CpUser;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.domain.WorkflowCase;
import com.crisil.covenant.dto.WorkflowCaseRequest;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.service.WorkflowCaseContext.CaseOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Opens a workflow <em>case</em> per CP when assessment starts (one case per CP per run) and resolves
 * the RM assignee it is opened with. Covenants of the same CP within one run share the case; the
 * returned {@code caseId} is stamped on every resulting {@code cov_assessment} row.
 *
 * <p><b>Never blocks the assessment.</b> The RM assignee is resolved best-effort (cache, then live
 * lookup), and a case is opened even when the RM cannot be determined. If case creation itself fails
 * the outcome carries an error message (persisted as {@code workflow_error}) rather than throwing.
 */
@Service
public class WorkflowCaseService {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowCaseService.class);
    private static final String WORKFLOW_CODE = "Covenant_Monitoring";

    private final WorkflowClient workflowClient;
    private final CreditProposalCacheRepository cacheRepo;
    private final CreditProposalClient creditProposalClient;

    public WorkflowCaseService(WorkflowClient workflowClient,
                               CreditProposalCacheRepository cacheRepo,
                               CreditProposalClient creditProposalClient) {
        this.workflowClient = workflowClient;
        this.cacheRepo = cacheRepo;
        this.creditProposalClient = creditProposalClient;
    }

    /** A fresh run context — see {@link WorkflowCaseContext} for what a "run" is. */
    public WorkflowCaseContext newRunContext() {
        return new WorkflowCaseContext();
    }

    /**
     * Ensure a workflow case exists for {@code cpId} within {@code ctx}, opening it at most once per
     * run. All covenants of the CP in the same run share the returned outcome.
     */
    public CaseOutcome ensureCase(String cpId, WorkflowCaseContext ctx) {
        return ctx.computeIfAbsent(cpId, () -> createCaseForCp(cpId));
    }

    private CaseOutcome createCaseForCp(String cpId) {
        String rm = resolveRm(cpId);
        try {
            WorkflowCase created = workflowClient.startCase(
                    new WorkflowCaseRequest(WORKFLOW_CODE, new WorkflowCaseRequest.Variables(rm)));
            String caseId = created == null ? null : created.caseId();
            if (caseId == null || caseId.isBlank()) {
                return CaseOutcome.error("Workflow returned empty body");
            }
            // Fire the three downstream steps once, right after the case is opened. They are
            // placeholders today; wrap them so a step failure can never lose the caseId we must persist.
            fireDownstreamSteps(caseId);
            return CaseOutcome.ok(caseId);
        } catch (RuntimeException ex) {
            return CaseOutcome.error(sanitise(ex.getMessage()));
        }
    }

    private void fireDownstreamSteps(String caseId) {
        try {
            workflowClient.submitCaseStep1(caseId);
            workflowClient.submitCaseStep2(caseId);
            workflowClient.submitCaseStep3(caseId);
        } catch (RuntimeException ex) {
            LOG.warn("Workflow downstream step failed for case {}: {}",
                    sanitise(caseId), sanitise(ex.getMessage()));
        }
    }

    /**
     * The RM userId to assign the case to: the CP-attribute cache's {@code rm_user_id} first, else a
     * best-effort live CP lookup. May return {@code null}/blank — the case is opened regardless.
     */
    private String resolveRm(String cpId) {
        String cached = cacheRepo.findById(cpId)
                .map(CreditProposalCacheEntry::getRmUserId)
                .filter(id -> !id.isBlank())
                .orElse(null);
        if (cached != null) {
            return cached;
        }
        try {
            CreditProposalDetails details = creditProposalClient.lookup(cpId);
            if (details != null) {
                CpUser rm = details.rm();
                if (rm != null) {
                    return rm.userId();
                }
            }
        } catch (RuntimeException ex) {
            LOG.warn("RM lookup failed for CP {}: {}", sanitise(cpId), sanitise(ex.getMessage()));
        }
        return null;
    }

    /** CR/LF-strip an external value so it is taint-safe at the log sink / stored error field. */
    private static String sanitise(String value) {
        return value == null ? "null" : value.replace('\n', '_').replace('\r', '_');
    }
}
