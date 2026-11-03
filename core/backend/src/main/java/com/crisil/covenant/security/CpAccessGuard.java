package com.crisil.covenant.security;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.domain.CpUser;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.service.CreditProposalClient;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Per-CP authorisation (R0/T2). Every CP-scoped endpoint calls one of the {@code require*} methods
 * before touching data.
 *
 * <p>Access is granted when the caller:
 * <ul>
 *   <li>has role ADMIN, SUPPORT or SERVICE; or</li>
 *   <li>has role AUDITOR and the request is a read (GET); or</li>
 *   <li>is the CP's RM or WRM, by the CP cache first, then a live credit-proposal lookup.</li>
 * </ul>
 * Otherwise it throws 404 {@code CP_NOT_FOUND}, so a caller cannot learn that another portfolio's
 * CP exists. A caller with no identity is always refused (fail-closed).
 */
@Component
public class CpAccessGuard {

    private static final Logger LOG = LoggerFactory.getLogger(CpAccessGuard.class);
    private static final Set<String> BYPASS = Set.of(
            "ROLE_" + Roles.ADMIN, "ROLE_" + Roles.SUPPORT, "ROLE_" + Roles.SERVICE);
    private static final String READ_ONLY_BYPASS = "ROLE_" + Roles.AUDITOR;

    private final CreditProposalCacheRepository cacheRepo;
    private final CreditProposalClient creditProposalClient;
    private final AppliedCovenantRepository appliedRepo;
    private final AssessmentRepository assessmentRepo;

    public CpAccessGuard(CreditProposalCacheRepository cacheRepo,
                         CreditProposalClient creditProposalClient,
                         AppliedCovenantRepository appliedRepo,
                         AssessmentRepository assessmentRepo) {
        this.cacheRepo = cacheRepo;
        this.creditProposalClient = creditProposalClient;
        this.appliedRepo = appliedRepo;
        this.assessmentRepo = assessmentRepo;
    }

    /** Refuses (404) unless the current caller may access {@code cpId}. */
    public void requireCp(String cpId) {
        if (!canAccess(cpId)) {
            throw denied();
        }
    }

    /** Resolves the applied covenant's CP and checks access; unknown ids are 404 as well. */
    public void requireAppliedCovenant(String appliedCovenantId) {
        String cpId = appliedRepo.findById(appliedCovenantId).map(AppliedCovenant::getCpId).orElse(null);
        if (cpId == null || !canAccess(cpId)) {
            throw denied();
        }
    }

    /** Resolves the assessment's CP and checks access; unknown ids are 404 as well. */
    public void requireAssessment(String assessmentId) {
        String cpId = assessmentRepo.findById(assessmentId)
                .map(Assessment::getAppliedCovenant)
                .map(AppliedCovenant::getCpId)
                .orElse(null);
        if (cpId == null || !canAccess(cpId)) {
            throw denied();
        }
    }

    /** Whether the current caller may access {@code cpId}. */
    public boolean canAccess(String cpId) {
        if (cpId == null || cpId.isBlank()) {
            return false;
        }
        Set<String> authorities = currentAuthorities();
        if (authorities.stream().anyMatch(BYPASS::contains)) {
            return true;
        }
        if (authorities.contains(READ_ONLY_BYPASS) && isReadRequest()) {
            return true;
        }
        Optional<String> user = CurrentUserContext.currentUser();
        if (user.isEmpty()) {
            return false;
        }
        if (cacheRepo.findCpIdsOwnedBy(user.get()).contains(cpId)) {
            return true;
        }
        return ownsByLiveLookup(cpId, user.get());
    }

    private boolean ownsByLiveLookup(String cpId, String user) {
        try {
            CreditProposalDetails details = creditProposalClient.lookup(cpId);
            return details != null && (isUser(details.rm(), user) || isUser(details.wrm(), user));
        } catch (RuntimeException ex) {
            LOG.warn("CP ownership lookup failed for {}; denying", cpId.replace('\n', '_').replace('\r', '_'));
            return false;
        }
    }

    private static boolean isUser(CpUser cpUser, String user) {
        return cpUser != null && user.equals(cpUser.userId());
    }

    private static Set<String> currentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Set.of();
        }
        Collection<? extends GrantedAuthority> granted = auth.getAuthorities();
        return granted.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private static boolean isReadRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                && "GET".equals(attrs.getRequest().getMethod()); // servlet method names are upper-case
    }

    private static ApiException denied() {
        return new ApiException(HttpStatus.NOT_FOUND, "CP_NOT_FOUND", "Credit proposal not found");
    }
}
