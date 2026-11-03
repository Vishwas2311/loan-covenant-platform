package com.crisil.covenant.config;

import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The current user's id.
 *
 * <p>Resolution order: (1) the authenticated Spring Security principal (JWT subject/username in
 * production, the {@code X-User} value in dev-header mode); (2) only when header fallback is enabled
 * (dev/test and plain unit tests), the raw {@code X-User} request header. In JWT mode
 * {@code SecurityConfig} disables the fallback, so a client-supplied header is never trusted.
 */
public final class CurrentUserContext {

    public static final String USER_HEADER = "X-User";

    private static volatile boolean headerFallbackEnabled = true;

    private CurrentUserContext() {}

    /** Set by {@code SecurityConfig}: {@code false} in JWT mode, {@code true} in dev-header mode. */
    public static void setHeaderFallbackEnabled(boolean enabled) {
        headerFallbackEnabled = enabled;
    }

    public static boolean isHeaderFallbackEnabled() {
        return headerFallbackEnabled;
    }

    /** Whether the authenticated principal holds any of {@code roles} (Spring authorities {@code ROLE_x}). */
    public static boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return false;
        }
        for (var granted : auth.getAuthorities()) {
            for (String role : roles) {
                if (("ROLE_" + role).equals(granted.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Optional<String> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            String name = auth.getName();
            if (name != null && !name.isBlank()) {
                return Optional.of(name);
            }
        }
        if (headerFallbackEnabled
                && RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String header = attrs.getRequest().getHeader(USER_HEADER);
            if (header != null && !header.isBlank()) {
                return Optional.of(header);
            }
        }
        return Optional.empty();
    }
}
