package com.crisil.covenant.config;

import com.crisil.covenant.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The access gate over the production-support surface ({@code /api/v1/ops/**}). Every ops request must
 * present an {@code X-Support-Token} header matching the configured secret; the gate is
 * <strong>fail-closed</strong> so a mis-provisioned deployment can never expose the surface
 * unauthenticated:
 * <ul>
 *   <li>{@code enabled=false} → pass through untouched (local/dev, surface trusted);</li>
 *   <li>{@code enabled=true} but no token configured → {@code 503 SUPPORT_DISABLED} (never allow-all);</li>
 *   <li>header absent/blank → {@code 401 SUPPORT_TOKEN_MISSING};</li>
 *   <li>header present but wrong → {@code 403 SUPPORT_TOKEN_INVALID}.</li>
 * </ul>
 *
 * <p>The comparison uses {@link MessageDigest#isEqual(byte[], byte[])} for a length-independent,
 * constant-time check that does not leak the secret through timing. Rejections throw {@link
 * ApiException}, which the existing {@code GlobalExceptionHandler} advice renders as the standard
 * {@code ErrorResponse} shape — no manual JSON here.
 */
public class SupportTokenInterceptor implements HandlerInterceptor {

    /** Header carrying the shared support secret. */
    public static final String TOKEN_HEADER = "X-Support-Token";

    private final SupportProperties properties;

    public SupportTokenInterceptor(SupportProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled()) {
            return true;
        }
        String configured = properties.getToken();
        if (configured == null || configured.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SUPPORT_DISABLED",
                    "Support surface is enabled but no token is configured");
        }
        String presented = request.getHeader(TOKEN_HEADER);
        if (presented == null || presented.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SUPPORT_TOKEN_MISSING",
                    "Missing " + TOKEN_HEADER + " header");
        }
        if (!constantTimeEquals(configured, presented)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUPPORT_TOKEN_INVALID",
                    "Invalid support token");
        }
        return true;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
