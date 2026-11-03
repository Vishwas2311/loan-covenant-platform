package com.crisil.covenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
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
    void decoderRequiresIssuerOrJwks() {
        assertThatThrownBy(() -> SecurityConfig.buildDecoder(new SecurityProperties.Jwt()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-uri");
    }

    @Test
    void decoderBuildsFromJwksWithoutNetworkCall() {
        SecurityProperties.Jwt cfg = new SecurityProperties.Jwt();
        cfg.setJwkSetUri("https://login.example/keys");
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
    void validatorWithoutIssuerOrAudienceOnlyChecksTimestamps() {
        OAuth2TokenValidator<Jwt> v = SecurityConfig.validator(new SecurityProperties.Jwt());
        assertThat(v.validate(token("https://any.example", List.of("x"))).hasErrors()).isFalse();
    }
}
