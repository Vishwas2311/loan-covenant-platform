package com.crisil.covenant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the {@link SupportTokenInterceptor} onto the production-support surface only. All ops
 * endpoints live under {@code /api/v1/ops/**}; every other route (the regular application API) is
 * untouched by the token gate.
 */
@Configuration
public class OpsWebMvcConfig implements WebMvcConfigurer {

    private final SupportProperties supportProperties;

    public OpsWebMvcConfig(SupportProperties supportProperties) {
        this.supportProperties = supportProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SupportTokenInterceptor(supportProperties))
                .addPathPatterns("/api/v1/ops/**");
    }
}
