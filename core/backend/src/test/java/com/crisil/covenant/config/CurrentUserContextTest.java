package com.crisil.covenant.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CurrentUserContextTest {

    @AfterEach
    void clearAttrs() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        CurrentUserContext.setHeaderFallbackEnabled(true);
    }

    @Test
    void authenticatedPrincipalWinsOverHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CurrentUserContext.USER_HEADER, "mallory");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));

        assertThat(CurrentUserContext.currentUser()).contains("alice");
    }

    @Test
    void anonymousPrincipalIsIgnored() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "k", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(CurrentUserContext.currentUser()).isEmpty();
    }

    @Test
    void headerIgnoredWhenFallbackDisabled() {
        CurrentUserContext.setHeaderFallbackEnabled(false);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CurrentUserContext.USER_HEADER, "alice");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThat(CurrentUserContext.isHeaderFallbackEnabled()).isFalse();
        assertThat(CurrentUserContext.currentUser()).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoRequestAttributes() {
        RequestContextHolder.resetRequestAttributes();
        assertThat(CurrentUserContext.currentUser()).isEmpty();
    }

    @Test
    void returnsHeaderWhenPresent() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CurrentUserContext.USER_HEADER, "alice");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThat(CurrentUserContext.currentUser()).contains("alice");
    }

    @Test
    void returnsEmptyWhenHeaderBlank() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CurrentUserContext.USER_HEADER, "  ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThat(CurrentUserContext.currentUser()).isEmpty();
    }

    @Test
    void returnsEmptyWhenHeaderMissing() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThat(CurrentUserContext.currentUser()).isEmpty();
    }

    @Test
    void requestWrappingDoesNotLeak() {
        HttpServletRequest req = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        RequestContextHolder.resetRequestAttributes();
        assertThat(CurrentUserContext.currentUser()).isEmpty();
    }
}
