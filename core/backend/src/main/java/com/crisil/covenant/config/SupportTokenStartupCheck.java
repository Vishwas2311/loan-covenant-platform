package com.crisil.covenant.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Fails application startup when the production-support gate is enabled but no token is configured.
 * The base configuration deliberately has no fallback token, so a deployment that forgets
 * {@code COVENANT_SUPPORT_TOKEN} refuses to start instead of running with a well-known secret.
 */
@Component
public class SupportTokenStartupCheck implements InitializingBean {

    private final SupportProperties properties;

    public SupportTokenStartupCheck(SupportProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.isEnabled()
                && (properties.getToken() == null || properties.getToken().isBlank())) {
            throw new IllegalStateException(
                    "covenant.support.enabled=true but no support token is configured; "
                            + "set COVENANT_SUPPORT_TOKEN or disable the support surface");
        }
    }
}
