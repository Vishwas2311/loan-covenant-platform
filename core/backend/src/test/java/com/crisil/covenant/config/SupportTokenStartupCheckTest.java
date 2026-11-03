package com.crisil.covenant.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SupportTokenStartupCheckTest {

    private static SupportProperties props(boolean enabled, String token) {
        SupportProperties p = new SupportProperties();
        p.setEnabled(enabled);
        p.setToken(token);
        return p;
    }

    @Test
    void enabledWithoutTokenFailsStartup() {
        assertThatThrownBy(() -> new SupportTokenStartupCheck(props(true, null)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COVENANT_SUPPORT_TOKEN");
    }

    @Test
    void enabledWithBlankTokenFailsStartup() {
        assertThatThrownBy(() -> new SupportTokenStartupCheck(props(true, "  ")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enabledWithTokenStarts() {
        assertThatCode(() -> new SupportTokenStartupCheck(props(true, "s3cret")).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void disabledWithoutTokenStarts() {
        assertThatCode(() -> new SupportTokenStartupCheck(props(false, null)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
