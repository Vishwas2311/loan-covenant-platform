package com.crisil.covenant.security;

import com.crisil.covenant.config.CurrentUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DEV/TEST ONLY. Authenticates the request as the {@code X-User} header value, with roles from the
 * comma-separated {@code X-Roles} header (default {@code RM}). Requests without {@code X-User} stay
 * anonymous, which keeps the original fail-closed portfolio behaviour.
 */
public class DevHeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String ROLES_HEADER = "X-Roles";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String user = request.getHeader(CurrentUserContext.USER_HEADER);
        if (user != null && !user.isBlank()) {
            List<SimpleGrantedAuthority> authorities = rolesFrom(request.getHeader(ROLES_HEADER)).stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(user.trim(), null, authorities));
        }
        chain.doFilter(request, response);
    }

    static List<String> rolesFrom(String header) {
        if (header == null || header.isBlank()) {
            return List.of(Roles.RM);
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
