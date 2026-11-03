package com.crisil.covenant.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication settings ({@code covenant.security.*}).
 *
 * <ul>
 *   <li>{@code mode=JWT} (production default): every API call needs a valid Entra ID bearer token;
 *       the user id comes from the configured principal claim and roles from the roles claim.</li>
 *   <li>{@code mode=DEV_HEADER} (dev/test only): the caller is whoever the {@code X-User} header
 *       says, with roles from {@code X-Roles} (default {@code RM}). Never enable in production.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "covenant.security")
public class SecurityProperties {

    public enum Mode { JWT, DEV_HEADER }

    private Mode mode = Mode.JWT;
    private final Jwt jwt = new Jwt();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Jwt getJwt() {
        return jwt;
    }

    /** Token validation settings for {@link Mode#JWT}. */
    public static class Jwt {
        /** Issuer, e.g. {@code https://login.microsoftonline.com/<tenant>/v2.0}. */
        private String issuerUri;
        /** JWKS endpoint; when blank it is discovered from the issuer. */
        private String jwkSetUri;
        /** Expected {@code aud} (the API's app id URI / client id). Blank skips the audience check. */
        private String audience;
        /** Claim holding the user id shown in audits and used for portfolio ownership. */
        private String principalClaim = "preferred_username";
        /** Claim holding app roles (RM, APPROVER, AUTHOR, SUPPORT, AUDITOR, SERVICE, ADMIN). */
        private String rolesClaim = "roles";

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getJwkSetUri() {
            return jwkSetUri;
        }

        public void setJwkSetUri(String jwkSetUri) {
            this.jwkSetUri = jwkSetUri;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getPrincipalClaim() {
            return principalClaim;
        }

        public void setPrincipalClaim(String principalClaim) {
            this.principalClaim = principalClaim;
        }

        public String getRolesClaim() {
            return rolesClaim;
        }

        public void setRolesClaim(String rolesClaim) {
            this.rolesClaim = rolesClaim;
        }
    }
}
