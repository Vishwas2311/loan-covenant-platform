package com.crisil.covenant.security;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Maps an Entra ID token to an authentication: the principal name comes from the configured claim
 * (falling back to {@code sub}), and each app role in the roles claim becomes {@code ROLE_<ROLE>}.
 */
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String principalClaim;
    private final String rolesClaim;

    public JwtAuthConverter(String principalClaim, String rolesClaim) {
        this.principalClaim = principalClaim;
        this.rolesClaim = rolesClaim;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String name = jwt.getClaimAsString(principalClaim);
        if (name == null || name.isBlank()) {
            name = jwt.getSubject();
        }
        return new JwtAuthenticationToken(jwt, authorities(jwt), name);
    }

    private Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(rolesClaim);
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(
                        "ROLE_" + r.trim().toUpperCase(Locale.ROOT)))
                .toList();
    }
}
