package com.crisil.covenant.security;

import com.crisil.covenant.config.CurrentUserContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Authentication and coarse authorisation for every endpoint (R0/T1).
 *
 * <p>JWT mode (production default) validates Entra ID bearer tokens and enforces role rules:
 * support toolkit needs SUPPORT, the data-event hook needs SERVICE, library and data-source writes
 * need AUTHOR (or ADMIN); every other API call only needs a valid token. Per-CP ownership is
 * enforced separately by {@code CpAccessGuard}.
 *
 * <p>DEV_HEADER mode keeps the original trust-the-header behaviour for local runs and tests.
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC = {
        "/actuator/health", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    private static final String[] LIBRARY_WRITE_PATHS = {
        "/api/v1/covenants/**", "/api/v1/measures/**", "/api/v1/data-sources/**", "/api/v1/settings/**"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties props,
                                            ObjectProvider<JwtDecoder> jwtDecoder) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.sameOrigin()));

        if (props.getMode() == SecurityProperties.Mode.DEV_HEADER) {
            CurrentUserContext.setHeaderFallbackEnabled(true);
            http.addFilterBefore(new DevHeaderAuthenticationFilter(), AnonymousAuthenticationFilter.class)
                    .authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        CurrentUserContext.setHeaderFallbackEnabled(false);
        SecurityProperties.Jwt jwt = props.getJwt();
        http.authorizeHttpRequests(a -> {
            a.requestMatchers(PUBLIC).permitAll();
            a.requestMatchers("/api/v1/ops/**").hasAnyRole(Roles.SUPPORT, Roles.ADMIN);
            a.requestMatchers("/api/v1/data-events/**").hasRole(Roles.SERVICE);
            // Portfolio-wide breach sweep + e-mails: operators and the scheduler only, not individual RMs.
            // Organisation settings and FX rates: ADMIN writes, everyone authenticated reads.
            for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
                a.requestMatchers(method, "/api/v1/settings/**", "/api/v1/fx/rates/**").hasRole(Roles.ADMIN);
            }
            // FR-12 maker-checker: only approvers decide Waiver/Extension requests.
            a.requestMatchers(HttpMethod.POST, "/api/v1/monitoring/cp/*/covenants/*/status/decision")
                    .hasAnyRole(Roles.APPROVER, Roles.ADMIN);
            a.requestMatchers(HttpMethod.POST, "/api/v1/monitoring/notification-runs")
                    .hasAnyRole(Roles.ADMIN, Roles.SUPPORT, Roles.SERVICE);
            for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                a.requestMatchers(method, LIBRARY_WRITE_PATHS).hasAnyRole(Roles.AUTHOR, Roles.ADMIN);
            }
            a.anyRequest().authenticated();
        }).oauth2ResourceServer(o -> o.jwt(j -> {
            j.jwtAuthenticationConverter(new JwtAuthConverter(jwt.getPrincipalClaim(), jwt.getRolesClaim()));
            JwtDecoder decoder = jwtDecoder.getIfAvailable(() -> buildDecoder(jwt));
            j.decoder(decoder);
        }));
        return http.build();
    }

    /** Builds a validating decoder from configuration; fails fast when nothing is configured. */
    static JwtDecoder buildDecoder(SecurityProperties.Jwt jwt) {
        boolean hasJwks = jwt.getJwkSetUri() != null && !jwt.getJwkSetUri().isBlank();
        boolean hasIssuer = jwt.getIssuerUri() != null && !jwt.getIssuerUri().isBlank();
        if (!hasJwks && !hasIssuer) {
            throw new IllegalStateException(
                    "covenant.security.mode=JWT needs covenant.security.jwt.issuer-uri or jwk-set-uri "
                            + "(or set covenant.security.mode=DEV_HEADER for local development only)");
        }
        NimbusJwtDecoder decoder = hasJwks
                ? NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri()).build()
                : NimbusJwtDecoder.withIssuerLocation(jwt.getIssuerUri()).build();
        decoder.setJwtValidator(validator(jwt));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(SecurityProperties.Jwt jwt) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        if (jwt.getIssuerUri() != null && !jwt.getIssuerUri().isBlank()) {
            validators.add(JwtValidators.createDefaultWithIssuer(jwt.getIssuerUri()));
        } else {
            validators.add(JwtValidators.createDefault());
        }
        String audience = jwt.getAudience();
        if (audience != null && !audience.isBlank()) {
            validators.add(new JwtClaimValidator<List<String>>("aud",
                    aud -> aud != null && aud.contains(audience)));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }
}
