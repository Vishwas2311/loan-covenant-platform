package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.dto.ManualRunView;
import com.crisil.covenant.service.ManualRunService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.RequestContextHolder;

@ExtendWith(MockitoExtension.class)
class NotificationRunControllerTest {

    @Mock private ManualRunService runs;
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
    void startAnswers202WithTheRunningRun() {
        LocalDate date = LocalDate.of(2026, 7, 8);
        ManualRunView running = new ManualRunView("run-1", "RUNNING", date, AuditingConfig.SYSTEM_USER,
                Instant.parse("2026-07-08T00:00:00Z"), null, null, null);
        when(runs.start(date, AuditingConfig.SYSTEM_USER)).thenReturn(running);

        var out = controller.run(date);

        assertThat(out.getStatusCode().value()).isEqualTo(202);
        assertThat(out.getBody()).isSameAs(running);
    }

    @Test
    void statusIsFoundOrNotFound() {
        ManualRunView done = new ManualRunView("run-2", "DONE", LocalDate.of(2026, 7, 8), "support-1",
                Instant.parse("2026-07-08T00:00:00Z"), Instant.parse("2026-07-08T00:05:00Z"), null, null);
        when(runs.find("run-2")).thenReturn(Optional.of(done));
        when(runs.find("nope")).thenReturn(Optional.empty());

        assertThat(controller.status("run-2").getBody()).isSameAs(done);
        assertThat(controller.status("nope").getStatusCode().value()).isEqualTo(404);
    }
}
