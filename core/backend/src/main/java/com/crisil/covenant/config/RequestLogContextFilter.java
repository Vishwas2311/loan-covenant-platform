package com.crisil.covenant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the request id and the signed-in user into every log line of a request ({@code requestId},
 * {@code user} in the log pattern), so one user's action can be followed through Core's log and, with
 * the same {@code X-Correlation-Id} the UI gateway sets, through DPS's. Runs after Spring Security, so
 * the user is known. The id is echoed back in the response.
 */
@Component
public class RequestLogContextFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        // Only a short plain id is taken from the caller, so a header cannot forge log lines.
        String id = given != null && SAFE_ID.matcher(given).matches() ? given : UUID.randomUUID().toString();
        MDC.put("requestId", id);
        MDC.put("user", CurrentUserContext.currentUser().map(u -> u.replace('\n', '_').replace('\r', '_'))
                .orElse("-"));
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
            MDC.remove("user");
        }
    }
}
