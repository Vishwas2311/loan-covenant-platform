package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.config.CurrentUserContext;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.crisil.covenant.service.MonitoringService;

/**
 * US-MON-09 / US-MON-15 portfolio scoping: the controller must resolve the caller from the {@code X-User}
 * request header (via {@link CurrentUserContext}) and forward it to every scoped monitoring read, so the
 * service can filter to that user's portfolio. When no request/header is bound it forwards {@code null},
 * which the service treats as fail-closed (empty portfolio).
 */
@ExtendWith(MockitoExtension.class)
class MonitoringControllerTest {

    @Mock private MonitoringService service;
    @Mock private CpAccessGuard guard;
    @InjectMocks private MonitoringController controller;

    @BeforeEach
    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void bindUser(String user) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CurrentUserContext.USER_HEADER, user);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void summaryForwardsXUserHeaderToService() {
        bindUser("rm-anil");
        when(service.summary("rm-anil")).thenReturn(List.of());

        assertThat(controller.summary()).isEmpty();
        verify(service).summary("rm-anil");
    }

    @Test
    void summaryForwardsNullWhenNoRequestBound() {
        when(service.summary(null)).thenReturn(List.of());

        assertThat(controller.summary()).isEmpty();
        verify(service).summary(null);
    }

    @Test
    void scheduleForwardsXUserHeaderToService() {
        bindUser("rm-anil");
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        when(service.schedule(from, to, null, null, null, "rm-anil")).thenReturn(List.of());

        assertThat(controller.schedule(from, to, null, null, null)).isEmpty();
        verify(service).schedule(from, to, null, null, null, "rm-anil");
    }

    @Test
    void covenantsForCpForwardsXUserHeaderToService() {
        bindUser("rm-anil");
        when(service.covenantsForCp("CP-1", "rm-anil")).thenReturn(List.of());

        assertThat(controller.covenantsForCp("CP-1")).isEmpty();
        verify(service).covenantsForCp("CP-1", "rm-anil");
    }

    @Test
    void historyDelegatesToServiceWithoutUserScoping() {
        when(service.historyFor("A-1", 0, 25)).thenReturn(List.of());

        assertThat(controller.history("A-1", 0, 25)).isEmpty();
        verify(service).historyFor("A-1", 0, 25);
    }
}
