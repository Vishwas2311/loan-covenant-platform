package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.ManualRunView;
import com.crisil.covenant.dto.RunResult;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** BUG-10: the manual daily run starts in the background, records its outcome, and never runs twice at once. */
@SpringBootTest
@ActiveProfiles("test")
class ManualRunServiceIT {

    @Autowired private JdbcTemplate jdbc;

    private final BreachNotificationService breachRun = mock(BreachNotificationService.class);
    private final EscalationService escalation = mock(EscalationService.class);
    private final List<Runnable> queued = new ArrayList<>();

    @AfterEach
    void clean() {
        jdbc.update("delete from cov_manual_run");
    }

    private ManualRunService service() {
        return new ManualRunService(breachRun, escalation, jdbc, queued::add, Clock.systemUTC());
    }

    @Test
    void aRunIsRecordedAsRunningThenDoneAndASecondStartReturnsTheSameRun() {
        LocalDate day = LocalDate.of(2026, 11, 6);
        when(breachRun.run(day, "support-1")).thenReturn(new RunResult("r", 7612, 1912, 22, 1));
        ManualRunService runs = service();

        ManualRunView started = runs.start(day, "support-1");
        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(started.result()).isNull();
        assertThat(runs.start(day, "admin-1").runId()).isEqualTo(started.runId()); // already running
        assertThat(queued).hasSize(1);

        queued.get(0).run();
        ManualRunView done = runs.find(started.runId()).orElseThrow();
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.finishedAt()).isNotNull();
        assertThat(done.result().covenantsAssessed()).isEqualTo(7612);
        assertThat(done.result().notificationsFailed()).isEqualTo(1);
        org.mockito.Mockito.verify(escalation).run("support-1"); // the digest follows, as in the daily job
        assertThat(runs.start(null, "support-1").runId()).isNotEqualTo(started.runId()); // a new run once done
    }

    @Test
    void aFailedRunKeepsItsReasonAndAnAbandonedRunDoesNotBlockANewOne() {
        when(breachRun.run(any(), any())).thenThrow(new IllegalStateException("notification service down"));
        when(escalation.run(any())).thenThrow(new IllegalStateException("never reached"));
        ManualRunService runs = service();
        ManualRunView started = runs.start(LocalDate.of(2026, 11, 6), "support-1");
        queued.get(0).run();
        ManualRunView failed = runs.find(started.runId()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).isEqualTo("notification service down");

        jdbc.update("insert into cov_manual_run (id, status, run_date, started_by, started_at) values "
                + "('stale', 'RUNNING', ?, 'x', ?)", LocalDate.of(2026, 11, 1),
                Timestamp.from(Instant.now().minus(ManualRunService.ABANDONED_AFTER).minusSeconds(60)));
        assertThat(runs.start(null, "support-1").runId()).isNotEqualTo("stale");
        assertThat(runs.find("missing")).isEmpty();
    }

    @Test
    void aFailingEscalationDoesNotFailTheRun() {
        LocalDate day = LocalDate.of(2026, 11, 9);
        when(breachRun.run(day, "support-1")).thenReturn(new RunResult("r", 1, 1, 1, 0));
        when(escalation.run("support-1")).thenThrow(new IllegalStateException("template missing"));
        ManualRunService runs = service();
        ManualRunView started = runs.start(day, "support-1");
        queued.get(0).run();
        assertThat(runs.find(started.runId()).orElseThrow().status()).isEqualTo("DONE");
    }
}
