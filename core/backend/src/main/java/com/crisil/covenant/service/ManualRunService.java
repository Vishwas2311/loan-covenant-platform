package com.crisil.covenant.service;

import com.crisil.covenant.dto.ManualRunView;
import com.crisil.covenant.dto.RunResult;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Starts the daily breach run from the Support screen without holding the HTTP request open: a large book
 * takes minutes, longer than the gateway waits (review BUG-10). The run's progress is kept in
 * {@code cov_manual_run}, so whichever Core instance answers can report it. Only one manual run goes at a
 * time; asking again while one is running returns that run. A run left RUNNING by a stopped instance is
 * ignored after {@link #ABANDONED_AFTER}.
 */
@Service
public class ManualRunService {

    static final Duration ABANDONED_AFTER = Duration.ofHours(3);
    private static final Logger LOG = LoggerFactory.getLogger(ManualRunService.class);

    private final BreachNotificationService breachRun;
    private final EscalationService escalation;
    private final JdbcTemplate jdbc;
    private final TaskExecutor executor;
    private final Clock clock;

    public ManualRunService(BreachNotificationService breachRun, EscalationService escalation, JdbcTemplate jdbc,
                            @Qualifier("applicationTaskExecutor") TaskExecutor executor, Clock clock) {
        this.breachRun = breachRun;
        this.escalation = escalation;
        this.jdbc = jdbc;
        this.executor = executor;
        this.clock = clock;
    }

    /** Starts a run for {@code date} (today when null), or returns the one already running. */
    public synchronized ManualRunView start(LocalDate date, String user) {
        Instant now = Instant.now(clock);
        Optional<ManualRunView> running = jdbc.query(
                "select * from cov_manual_run where status = 'RUNNING' and started_at > ? order by started_at desc",
                MAPPER, Timestamp.from(now.minus(ABANDONED_AFTER))).stream().findFirst();
        if (running.isPresent()) {
            return running.get();
        }
        LocalDate runDate = date != null ? date : LocalDate.now(ZoneId.systemDefault());
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into cov_manual_run (id, status, run_date, started_by, started_at) "
                + "values (?, 'RUNNING', ?, ?, ?)", id, runDate, user, Timestamp.from(now));
        executor.execute(() -> execute(id, runDate, user));
        return find(id).orElseThrow();
    }

    public Optional<ManualRunView> find(String id) {
        List<ManualRunView> rows = jdbc.query("select * from cov_manual_run where id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    void execute(String id, LocalDate runDate, String user) {
        try {
            RunResult r = breachRun.run(runDate, user);
            // The same e-mails as the daily job: the escalation digest follows the RM summaries (FR-31).
            try {
                escalation.run(user);
            } catch (RuntimeException ex) {
                LOG.error("Escalation after the manual run failed: {}", ex.getClass().getSimpleName());
            }
            jdbc.update("update cov_manual_run set status = 'DONE', finished_at = ?, covenants_assessed = ?, "
                            + "cps_touched = ?, notifications_sent = ?, notifications_failed = ? where id = ?",
                    Timestamp.from(Instant.now(clock)), r.covenantsAssessed(), r.cpsTouched(), r.notificationsSent(),
                    r.notificationsFailed(), id);
        } catch (RuntimeException ex) {
            LOG.error("Manual daily run failed: {}", ex.getClass().getSimpleName());
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            jdbc.update("update cov_manual_run set status = 'FAILED', finished_at = ?, error = ? where id = ?",
                    Timestamp.from(Instant.now(clock)), reason.substring(0, Math.min(1000, reason.length())), id);
        }
    }

    private static final RowMapper<ManualRunView> MAPPER = (rs, n) -> {
        Timestamp finished = rs.getTimestamp("finished_at");
        RunResult result = "DONE".equals(rs.getString("status"))
                ? new RunResult(rs.getString("id"), rs.getInt("covenants_assessed"), rs.getInt("cps_touched"),
                        rs.getInt("notifications_sent"), rs.getInt("notifications_failed"))
                : null;
        return new ManualRunView(rs.getString("id"), rs.getString("status"), rs.getDate("run_date").toLocalDate(),
                rs.getString("started_by"), rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(), result, rs.getString("error"));
    };
}
