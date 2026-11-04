package com.crisil.covenant.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Each request's log lines carry its id and user; a forged header cannot inject log text. */
class RequestLogContextFilterTest {

    private final RequestLogContextFilter filter = new RequestLogContextFilter();

    private Map<String, String> run(String header) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/x");
        if (header != null) {
            req.addHeader(RequestLogContextFilter.HEADER, header);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        Map<String, String> seen = new HashMap<>();
        filter.doFilter(req, res, (rq, rs) -> {
            seen.put("requestId", MDC.get("requestId"));
            seen.put("user", MDC.get("user"));
        });
        seen.put("echo", res.getHeader(RequestLogContextFilter.HEADER));
        assertThat(MDC.get("requestId")).as("cleared after the request").isNull();
        return seen;
    }

    @Test
    void keepsAPlainIdAndReplacesAnUnsafeOne() throws Exception {
        Map<String, String> kept = run("abc-123");
        assertThat(kept).containsEntry("requestId", "abc-123").containsEntry("echo", "abc-123")
                .containsEntry("user", "-");
        Map<String, String> replaced = run("bad\nINFO forged line");
        assertThat(replaced.get("requestId")).hasSize(36).doesNotContain("forged");
        assertThat(run(null).get("requestId")).hasSize(36);
    }
}
