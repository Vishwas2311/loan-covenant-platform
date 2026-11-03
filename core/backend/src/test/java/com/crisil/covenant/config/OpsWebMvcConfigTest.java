package com.crisil.covenant.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

class OpsWebMvcConfigTest {

    private static List<Object> registeredInterceptors(SupportProperties props) {
        OpsWebMvcConfig config = new OpsWebMvcConfig(props);
        InterceptorRegistry registry = new InterceptorRegistry();
        config.addInterceptors(registry);
        @SuppressWarnings("unchecked")
        List<Object> registrations =
                (List<Object>) ReflectionTestUtils.invokeMethod(registry, "getInterceptors");
        return registrations;
    }

    private static HandlerInterceptor unwrap(Object entry) {
        // A path-scoped registration is wrapped in a MappedInterceptor.
        if (entry instanceof MappedInterceptor mapped) {
            return mapped.getInterceptor();
        }
        return (HandlerInterceptor) entry;
    }

    @Test
    void registersSupportTokenInterceptor() {
        SupportProperties props = new SupportProperties();
        props.setEnabled(true);
        props.setToken("s3cret");

        List<Object> registrations = registeredInterceptors(props);

        assertThat(registrations).hasSize(1);
        assertThat(unwrap(registrations.get(0))).isInstanceOf(SupportTokenInterceptor.class);
    }

    @Test
    void interceptorIsScopedToOpsPath() {
        SupportProperties props = new SupportProperties();
        props.setEnabled(false);

        Object entry = registeredInterceptors(props).get(0);

        assertThat(entry).isInstanceOf(MappedInterceptor.class);
        assertThat(unwrap(entry)).isInstanceOf(SupportTokenInterceptor.class);
    }
}
