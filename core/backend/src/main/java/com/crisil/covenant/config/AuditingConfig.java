package com.crisil.covenant.config;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

@Configuration
public class AuditingConfig {

    public static final String SYSTEM_USER = "system";

    @Bean
    public AuditorAware<String> auditorProvider() {
        return new RequestHeaderAuditorAware();
    }

    static class RequestHeaderAuditorAware implements AuditorAware<String> {

        @Override
        public Optional<String> getCurrentAuditor() {
            return CurrentUserContext.currentUser().or(() -> Optional.of(SYSTEM_USER));
        }
    }
}
