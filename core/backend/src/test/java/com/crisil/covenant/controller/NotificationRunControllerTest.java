package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.dto.RunResult;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.RequestContextHolder;
import com.crisil.covenant.service.BreachNotificationService;

@ExtendWith(MockitoExtension.class)
class NotificationRunControllerTest {

    @Mock private BreachNotificationService service;
    @InjectMocks private NotificationRunController controller;

    @BeforeEach
    @AfterEach
    void clearRequestContext() {
        // No web request is bound in a plain unit test, so CurrentUserContext.currentUser() is empty
        // and the controller must fall back to the system user. Reset defensively in case an earlier
        // test on this thread left request attributes bound.
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void runDelegatesWithSelectedDateAndSystemUser() {
        LocalDate date = LocalDate.of(2026, 7, 8);
        RunResult expected = new RunResult("run-1", 5, 3, 3, 0);
        when(service.run(eq(date), eq(AuditingConfig.SYSTEM_USER))).thenReturn(expected);

        RunResult out = controller.run(date);

        assertThat(out).isSameAs(expected);
        verify(service).run(date, AuditingConfig.SYSTEM_USER);
    }

    @Test
    void runWithoutDateDelegatesNullSoServiceDefaultsToToday() {
        RunResult expected = new RunResult("run-2", 0, 0, 0, 0);
        when(service.run(eq(null), eq(AuditingConfig.SYSTEM_USER))).thenReturn(expected);

        RunResult out = controller.run(null);

        assertThat(out).isSameAs(expected);
        verify(service).run(null, AuditingConfig.SYSTEM_USER);
    }
}
