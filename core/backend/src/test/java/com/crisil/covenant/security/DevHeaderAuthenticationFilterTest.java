package com.crisil.covenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class DevHeaderAuthenticationFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication run(MockHttpServletRequest req) throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        new DevHeaderAuthenticationFilter().doFilter(req, new MockHttpServletResponse(),
                (rq, rs) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
        return seen.get();
    }

    @Test
    void authenticatesFromHeaderWithDefaultRmRole() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User", " rm-anil ");
        Authentication auth = run(req);
        assertThat(auth.getName()).isEqualTo("rm-anil");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_RM");
    }

    @Test
    void readsRolesHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User", "sup-1");
        req.addHeader(DevHeaderAuthenticationFilter.ROLES_HEADER, "support, approver,,SUPPORT");
        Authentication auth = run(req);
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SUPPORT", "ROLE_APPROVER");
    }

    @Test
    void staysAnonymousWithoutHeader() throws Exception {
        assertThat(run(new MockHttpServletRequest())).isNull();
        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.addHeader("X-User", "  ");
        assertThat(run(blank)).isNull();
    }

    @Test
    void blankRolesHeaderDefaultsToRm() {
        assertThat(DevHeaderAuthenticationFilter.rolesFrom("  ")).containsExactly(Roles.RM);
        assertThat(DevHeaderAuthenticationFilter.rolesFrom(null)).containsExactly(Roles.RM);
    }
}
