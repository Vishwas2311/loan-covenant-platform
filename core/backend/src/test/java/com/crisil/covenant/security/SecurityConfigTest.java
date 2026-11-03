package com.crisil.covenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class SecurityConfigTest {

    private static Jwt token(String iss, List<String> aud) {
        return Jwt.withTokenValue("t").header("alg", "none").issuer(iss).audience(aud)
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300))
                .subject("s").build();
    }

    @Test
    void decoderRequiresIssuerAndAudience() {
        assertThatThrownBy(() -> SecurityConfig.buildDecoder(new SecurityProperties.Jwt()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-uri and audience");

        SecurityProperties.Jwt jwksOnly = new SecurityProperties.Jwt();
        jwksOnly.setJwkSetUri("https://login.example/keys");
        assertThatThrownBy(() -> SecurityConfig.buildDecoder(jwksOnly))
                .isInstanceOf(IllegalStateException.class);

        SecurityProperties.Jwt noAudience = new SecurityProperties.Jwt();
        noAudience.setJwkSetUri("https://login.example/keys");
        noAudience.setIssuerUri("https://issuer.example/v2.0");
        assertThatThrownBy(() -> SecurityConfig.buildDecoder(noAudience))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decoderBuildsFromJwksWithoutNetworkCall() {
        SecurityProperties.Jwt cfg = new SecurityProperties.Jwt();
        cfg.setJwkSetUri("https://login.example/keys");
        cfg.setIssuerUri("https://issuer.example/v2.0");
        cfg.setAudience("api://covenant-core");
        JwtDecoder decoder = SecurityConfig.buildDecoder(cfg);
        assertThat(decoder).isNotNull();
    }

    @Test
    void validatorChecksIssuerAndAudience() {
        SecurityProperties.Jwt cfg = new SecurityProperties.Jwt();
        cfg.setIssuerUri("https://issuer.example/v2.0");
        cfg.setAudience("api://covenant-core");
        OAuth2TokenValidator<Jwt> v = SecurityConfig.validator(cfg);

        assertThat(v.validate(token("https://issuer.example/v2.0", List.of("api://covenant-core"))).hasErrors())
                .isFalse();
        assertThat(v.validate(token("https://evil.example", List.of("api://covenant-core"))).hasErrors())
                .isTrue();
        assertThat(v.validate(token("https://issuer.example/v2.0", List.of("other"))).hasErrors()).isTrue();
    }

    @Test
    void devHeaderModeIsRefusedUnderTheProdProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod", "local-stack");

        assertThatThrownBy(() -> SecurityConfig.requireJwtInProduction(SecurityProperties.Mode.DEV_HEADER, prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEV_HEADER");
        assertThatCode(() -> SecurityConfig.requireJwtInProduction(SecurityProperties.Mode.JWT, prod))
                .doesNotThrowAnyException();
    }

    @Test
    void devHeaderModeIsAllowedOutsideProd() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");

        assertThatCode(() -> SecurityConfig.requireJwtInProduction(SecurityProperties.Mode.DEV_HEADER, dev))
                .doesNotThrowAnyException();
    }

    @Test
    void validatorWithoutIssuerOrAudienceOnlyChecksTimestamps() {
        OAuth2TokenValidator<Jwt> v = SecurityConfig.validator(new SecurityProperties.Jwt());
        assertThat(v.validate(token("https://any.example", List.of("x"))).hasErrors()).isFalse();
    }
}
