package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.covenant.entity.DataSource;
import com.crisil.covenant.enums.DataSourceType;
import com.crisil.covenant.exception.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DataSourceSecretsTest {

    @AfterEach
    void restoreEnvironment() {
        DataSourceSecrets.useEnvironment(null);
    }

    private static DataSource source(Map<String, String> config) {
        DataSource ds = new DataSource();
        ds.setName("MSSQL");
        ds.setType(DataSourceType.MSSQL);
        ds.setConnectionConfig(new LinkedHashMap<>(config));
        return ds;
    }

    @Test
    void readsPasswordFromReferencedEnvironmentVariable() {
        DataSourceSecrets.useEnvironment(
                name -> "COVENANT_DS_FIN".equals(name) ? "from-vault" : null);

        assertThat(DataSourceSecrets.password(source(Map.of("secretRef", "COVENANT_DS_FIN"))))
                .isEqualTo("from-vault");
    }

    @Test
    void secretRefWinsOverPlaintextPassword() {
        DataSourceSecrets.useEnvironment(name -> "ref-value");

        assertThat(DataSourceSecrets.password(
                source(Map.of("secretRef", "COVENANT_DS_FIN", "password", "plain"))))
                .isEqualTo("ref-value");
    }

    @Test
    void fallsBackToPlaintextPasswordWithoutRef() {
        assertThat(DataSourceSecrets.password(source(Map.of("password", "plain")))).isEqualTo("plain");
        assertThat(DataSourceSecrets.password(source(Map.of("secretRef", " ", "password", "p"))))
                .isEqualTo("p");
        assertThat(DataSourceSecrets.password(source(Map.of()))).isNull();
    }

    @Test
    void missingReferencedVariableIsAClearError() {
        DataSourceSecrets.useEnvironment(name -> null);

        assertThatThrownBy(() -> DataSourceSecrets.password(source(Map.of("secretRef", "COVENANT_DS_X"))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("COVENANT_DS_X")
                .extracting("code").isEqualTo("DATA_SOURCE_SECRET_MISSING");
    }

    @Test
    void refusesVariablesOutsideTheAllowedPrefix() {
        DataSourceSecrets.useEnvironment(name -> "core-secret");

        for (String ref : new String[] {"MSSQL_SA_PASSWORD", "COVENANT_DS_lower", "COVENANT_DS_A-B"}) {
            assertThatThrownBy(() -> DataSourceSecrets.password(source(Map.of("secretRef", ref))))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("DATA_SOURCE_SECRET_REF_INVALID");
        }
    }
}
