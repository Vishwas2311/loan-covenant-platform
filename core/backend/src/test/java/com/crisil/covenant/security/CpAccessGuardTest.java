package com.crisil.covenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.domain.CpUser;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.AssessmentRepository;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import com.crisil.covenant.service.CreditProposalClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class CpAccessGuardTest {

    @Mock CreditProposalCacheRepository cacheRepo;
    @Mock CreditProposalClient cpClient;
    @Mock AppliedCovenantRepository appliedRepo;
    @Mock AssessmentRepository assessmentRepo;
    CpAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new CpAccessGuard(cacheRepo, cpClient, appliedRepo, assessmentRepo);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void loginAs(String user, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
    }

    private static void request(String method) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/x");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static void assertDenied(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void ownerByCacheIsAllowed() {
        loginAs("rm-anil", Roles.RM);
        when(cacheRepo.findCpIdsOwnedBy("rm-anil")).thenReturn(List.of("CP-1"));
        assertThatCode(() -> guard.requireCp("CP-1")).doesNotThrowAnyException();
        verify(cpClient, never()).lookup("CP-1");
    }

    @Test
    void ownerByLiveLookupIsAllowed() {
        loginAs("wrm-meera", Roles.RM);
        when(cacheRepo.findCpIdsOwnedBy("wrm-meera")).thenReturn(List.of());
        when(cpClient.lookup("CP-2")).thenReturn(new CreditProposalDetails("CP-2", "Co", null, null, null, null,
                new CpUser("rm-x", null, null), new CpUser("wrm-meera", null, null), null, null));
        assertThat(guard.canAccess("CP-2")).isTrue();
    }

    @Test
    void nonOwnerIsDenied() {
        loginAs("rm-other", Roles.RM);
        when(cacheRepo.findCpIdsOwnedBy("rm-other")).thenReturn(List.of("CP-9"));
        when(cpClient.lookup("CP-1")).thenReturn(new CreditProposalDetails("CP-1", "Co", null, null, null, null,
                new CpUser("rm-anil", null, null), null, null, null));
        assertDenied(() -> guard.requireCp("CP-1"));
    }

    @Test
    void lookupFailureOrNullDetailsDenies() {
        loginAs("rm-anil", Roles.RM);
        when(cacheRepo.findCpIdsOwnedBy("rm-anil")).thenReturn(List.of());
        when(cpClient.lookup("CP-1")).thenThrow(new RuntimeException("down\nnow"));
        assertThat(guard.canAccess("CP-1")).isFalse();
        when(cpClient.lookup("CP-3")).thenReturn(null);
        assertThat(guard.canAccess("CP-3")).isFalse();
    }

    @Test
    void anonymousAndBlankCpAreDenied() {
        assertThat(guard.canAccess("CP-1")).isFalse();
        loginAs("rm-anil", Roles.RM);
        assertThat(guard.canAccess(" ")).isFalse();
        assertThat(guard.canAccess(null)).isFalse();
    }

    @Test
    void bypassRolesAreAllowed() {
        for (String role : List.of(Roles.ADMIN, Roles.SUPPORT, Roles.SERVICE)) {
            loginAs("op", role);
            assertThat(guard.canAccess("CP-ANY")).as(role).isTrue();
        }
    }

    @Test
    void auditorCanOnlyRead() {
        loginAs("aud", Roles.AUDITOR);
        request("GET");
        assertThat(guard.canAccess("CP-1")).isTrue();
        request("POST");
        when(cacheRepo.findCpIdsOwnedBy("aud")).thenReturn(List.of());
        when(cpClient.lookup("CP-1")).thenReturn(null);
        assertThat(guard.canAccess("CP-1")).isFalse();
    }

    @Test
    void appliedCovenantResolvesCp() {
        loginAs("rm-anil", Roles.RM);
        AppliedCovenant ac = new AppliedCovenant();
        ac.setCpId("CP-1");
        when(appliedRepo.findById("ac-1")).thenReturn(Optional.of(ac));
        when(cacheRepo.findCpIdsOwnedBy("rm-anil")).thenReturn(List.of("CP-1"));
        assertThatCode(() -> guard.requireAppliedCovenant("ac-1")).doesNotThrowAnyException();

        when(appliedRepo.findById("missing")).thenReturn(Optional.empty());
        assertDenied(() -> guard.requireAppliedCovenant("missing"));
    }

    @Test
    void appliedCovenantOfOtherCpDenied() {
        loginAs("rm-anil", Roles.RM);
        AppliedCovenant ac = new AppliedCovenant();
        ac.setCpId("CP-OTHER");
        when(appliedRepo.findById("ac-2")).thenReturn(Optional.of(ac));
        when(cacheRepo.findCpIdsOwnedBy("rm-anil")).thenReturn(List.of("CP-1"));
        when(cpClient.lookup("CP-OTHER")).thenReturn(null);
        assertDenied(() -> guard.requireAppliedCovenant("ac-2"));
    }

    @Test
    void assessmentResolvesCp() {
        loginAs("rm-anil", Roles.RM);
        AppliedCovenant ac = new AppliedCovenant();
        ac.setCpId("CP-1");
        Assessment a = new Assessment();
        a.setAppliedCovenant(ac);
        when(assessmentRepo.findById("as-1")).thenReturn(Optional.of(a));
        when(cacheRepo.findCpIdsOwnedBy("rm-anil")).thenReturn(List.of("CP-1"));
        assertThatCode(() -> guard.requireAssessment("as-1")).doesNotThrowAnyException();

        when(assessmentRepo.findById("as-x")).thenReturn(Optional.empty());
        assertDenied(() -> guard.requireAssessment("as-x"));
    }

    @Test
    void assessmentOfOtherCpDenied() {
        loginAs("rm-anil", Roles.RM);
        AppliedCovenant ac = new AppliedCovenant();
        ac.setCpId("CP-OTHER");
        Assessment a = new Assessment();
        a.setAppliedCovenant(ac);
        when(assessmentRepo.findById("as-2")).thenReturn(Optional.of(a));
        when(cacheRepo.findCpIdsOwnedBy("rm-anil")).thenReturn(List.of());
        when(cpClient.lookup("CP-OTHER")).thenReturn(null);
        assertDenied(() -> guard.requireAssessment("as-2"));
    }
}
