package com.crisil.covenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtAuthConverterTest {

    private final JwtAuthConverter converter = new JwtAuthConverter("preferred_username", "roles");

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).subject("sub-123");
        claims.forEach(b::claim);
        return b.build();
    }

    @Test
    void usesPrincipalClaimAndMapsRoles() {
        AbstractAuthenticationToken auth = converter.convert(jwt(Map.of(
                "preferred_username", "anil@bank.example", "roles", List.of("rm", "Approver", " "))));
        assertThat(auth.getName()).isEqualTo("anil@bank.example");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_RM", "ROLE_APPROVER");
    }

    @Test
    void fallsBackToSubjectAndNoRoles() {
        AbstractAuthenticationToken auth = converter.convert(jwt(Map.of("preferred_username", " ")));
        assertThat(auth.getName()).isEqualTo("sub-123");
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void missingPrincipalClaimUsesSubject() {
        AbstractAuthenticationToken auth = converter.convert(jwt(Map.of("roles", List.of("SUPPORT"))));
        assertThat(auth.getName()).isEqualTo("sub-123");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_SUPPORT");
    }
}
