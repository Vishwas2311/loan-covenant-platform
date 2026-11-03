package com.crisil.covenant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the production-support ({@code /api/v1/ops/**}) toolkit. Kept out of
 * code so operations can rotate the token or disable the whole surface without a redeploy.
 * <ul>
 *   <li>{@code enabled} — master switch for the token gate. When {@code true} (the production default)
 *       every ops call must present a matching {@code X-Support-Token}. When {@code false} the gate
 *       passes through untouched (local/dev, where the surface is trusted).</li>
 *   <li>{@code token} — the shared secret compared, in constant time, against the request header. When
 *       the gate is {@code enabled} this MUST be configured; a blank token fails closed (503) so a
 *       mis-provisioned deployment can never accidentally expose the surface unauthenticated.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "covenant.support")
public class SupportProperties {

    private boolean enabled = true;
    private String token;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
