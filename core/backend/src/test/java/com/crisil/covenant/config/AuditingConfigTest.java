package com.crisil.covenant.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AuditorAware;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuditingConfigTest {

    private final AuditorAware<String> aware = new AuditingConfig().auditorProvider();

    @AfterEach
    void clearAttrs() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void defaultsToSystemWhenNoUserHeader() {
        RequestContextHolder.resetRequestAttributes();
        assertThat(aware.getCurrentAuditor()).contains(AuditingConfig.SYSTEM_USER);
    }

    @Test
    void usesHeaderWhenProvided() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CurrentUserContext.USER_HEADER, "alice");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThat(aware.getCurrentAuditor()).contains("alice");
    }
}
