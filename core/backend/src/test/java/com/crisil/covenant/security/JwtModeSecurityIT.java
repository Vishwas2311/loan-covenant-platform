package com.crisil.covenant.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crisil.covenant.config.CurrentUserContext;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boots Core in JWT mode with a locally generated RSA key standing in for Entra ID, and checks the
 * role rules end to end through the real filter chain.
 */
@SpringBootTest(properties = {
    "covenant.security.mode=JWT",
    "covenant.security.jwt.issuer-uri=https://issuer.test/v2.0",
    "covenant.security.jwt.audience=api://covenant-core"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtModeSecurityIT {

    static final RSAKey KEY = generate();
    static final String ISSUER = "https://issuer.test/v2.0";
    static final String AUD = "api://covenant-core";

    @Autowired MockMvc mvc;

    @AfterAll
    static void restoreFallback() {
        // Other test contexts in the same JVM run in DEV_HEADER mode.
        CurrentUserContext.setHeaderFallbackEnabled(true);
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class Keys {
        @Bean
        JwtDecoder jwtDecoder() throws JOSEException {
            NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            SecurityProperties.Jwt cfg = new SecurityProperties.Jwt();
            cfg.setIssuerUri(ISSUER);
            cfg.setAudience(AUD);
            d.setJwtValidator(SecurityConfig.validator(cfg));
            return d;
        }
    }

    static String token(String user, List<String> roles, String issuer, String aud) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer).audience(aud).subject("sub-" + user)
                .claim("preferred_username", user).claim("roles", roles)
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return "Bearer " + jwt.serialize();
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void apiRequiresToken() throws Exception {
        mvc.perform(get("/api/v1/covenants")).andExpect(status().isUnauthorized());
    }

    @Test
    void headerAloneIsNotTrusted() throws Exception {
        mvc.perform(get("/api/v1/covenants").header("X-User", "rm-anil")).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenCanRead() throws Exception {
        mvc.perform(get("/api/v1/covenants").header("Authorization", token("rm-anil", List.of("RM"), ISSUER, AUD)))
                .andExpect(status().isOk());
    }

    @Test
    void wrongIssuerOrAudienceRejected() throws Exception {
        mvc.perform(get("/api/v1/covenants")
                        .header("Authorization", token("rm-anil", List.of("RM"), "https://evil.test", AUD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/covenants")
                        .header("Authorization", token("rm-anil", List.of("RM"), ISSUER, "other-api")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void libraryWriteNeedsAuthorRole() throws Exception {
        String body = "{}";
        mvc.perform(post("/api/v1/covenants").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", token("rm-anil", List.of("RM"), ISSUER, AUD)))
                .andExpect(status().isForbidden());
        // AUTHOR passes the security layer; the empty body then fails validation (400), not 403.
        mvc.perform(post("/api/v1/covenants").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", token("author-1", List.of("AUTHOR"), ISSUER, AUD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dataSourceWriteNeedsAdmin() throws Exception {
        String body = "{}";
        mvc.perform(post("/api/v1/data-sources").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", token("author-1", List.of("AUTHOR"), ISSUER, AUD)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/data-sources").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", token("admin-1", List.of("ADMIN"), ISSUER, AUD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void opsNeedsSupportRole() throws Exception {
        mvc.perform(get("/api/v1/ops/health/data-sources")
                        .header("Authorization", token("rm-anil", List.of("RM"), ISSUER, AUD)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ops/health/data-sources")
                        .header("Authorization", token("sup-1", List.of("SUPPORT"), ISSUER, AUD))
                        .header("X-Support-Token", "test-support-token"))
                .andExpect(status().isOk());
    }

    @Test
    void dataEventsNeedServiceRole() throws Exception {
        mvc.perform(post("/api/v1/data-events").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", token("rm-anil", List.of("RM"), ISSUER, AUD)))
                .andExpect(status().isForbidden());
    }
}
