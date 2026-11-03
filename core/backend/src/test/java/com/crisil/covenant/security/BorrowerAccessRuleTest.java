package com.crisil.covenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** C1: a borrower-only user is kept out of every bank endpoint; anyone else signed in is not. */
class BorrowerAccessRuleTest {

    private static UsernamePasswordAuthenticationToken user(String... roles) {
        return new UsernamePasswordAuthenticationToken("u", null,
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    @Test
    void borrowerOnlyIsRefusedButStaffAndMixedRolesPass() {
        assertThat(SecurityConfig.bankUser(user("BORROWER"))).isFalse();
        assertThat(SecurityConfig.bankUser(user("BORROWER", "RM"))).isTrue();
        assertThat(SecurityConfig.bankUser(user("RM"))).isTrue();
        assertThat(SecurityConfig.bankUser(user())).isTrue();
        assertThat(SecurityConfig.bankUser(null)).isFalse();
        assertThat(SecurityConfig.bankUser(new AnonymousAuthenticationToken("k", "anon",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))))).isFalse();
        UsernamePasswordAuthenticationToken notSignedIn = new UsernamePasswordAuthenticationToken("u", null);
        assertThat(SecurityConfig.bankUser(notSignedIn)).isFalse();
        UsernamePasswordAuthenticationToken scoped = new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("SCOPE_read"), new SimpleGrantedAuthority("ROLE_BORROWER")));
        assertThat(SecurityConfig.bankUser(scoped)).isFalse();
    }
}
