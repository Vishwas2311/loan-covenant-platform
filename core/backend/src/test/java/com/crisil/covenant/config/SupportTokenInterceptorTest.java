package com.crisil.covenant.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.covenant.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SupportTokenInterceptorTest {

    private static SupportProperties props(boolean enabled, String token) {
        SupportProperties p = new SupportProperties();
        p.setEnabled(enabled);
        p.setToken(token);
        return p;
    }

    private static SupportTokenInterceptor interceptor(boolean enabled, String token) {
        return new SupportTokenInterceptor(props(enabled, token));
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (token != null) {
            req.addHeader(SupportTokenInterceptor.TOKEN_HEADER, token);
        }
        return req;
    }

    @Test
    void passesThroughWhenDisabled() {
        boolean result = interceptor(false, null)
                .preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());
        assertThat(result).isTrue();
    }

    @Test
    void allowsWhenTokenMatches() {
        boolean result = interceptor(true, "s3cret")
                .preHandle(requestWithToken("s3cret"), new MockHttpServletResponse(), new Object());
        assertThat(result).isTrue();
    }

    @Test
    void failsClosedWithServiceUnavailableWhenEnabledButNoTokenConfigured() {
        assertThatThrownBy(() -> interceptor(true, "  ")
                .preHandle(requestWithToken("anything"), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(api.getCode()).isEqualTo("SUPPORT_DISABLED");
                });
    }

    @Test
    void failsClosedWhenTokenNull() {
        assertThatThrownBy(() -> interceptor(true, null)
                .preHandle(requestWithToken("anything"), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("SUPPORT_DISABLED");
    }

    @Test
    void rejectsMissingHeaderWithUnauthorized() {
        assertThatThrownBy(() -> interceptor(true, "s3cret")
                .preHandle(requestWithToken(null), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(api.getCode()).isEqualTo("SUPPORT_TOKEN_MISSING");
                });
    }

    @Test
    void rejectsBlankHeaderWithUnauthorized() {
        assertThatThrownBy(() -> interceptor(true, "s3cret")
                .preHandle(requestWithToken("   "), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("SUPPORT_TOKEN_MISSING");
    }

    @Test
    void rejectsWrongTokenWithForbidden() {
        assertThatThrownBy(() -> interceptor(true, "s3cret")
                .preHandle(requestWithToken("wrong"), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(api.getCode()).isEqualTo("SUPPORT_TOKEN_INVALID");
                });
    }

    @Test
    void rejectsTokenThatIsPrefixOfConfigured() {
        // Length-mismatched compare must still reject (constant-time compare handles unequal lengths).
        assertThatThrownBy(() -> interceptor(true, "s3cret")
                .preHandle(requestWithToken("s3cr"), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("SUPPORT_TOKEN_INVALID");
    }
}
