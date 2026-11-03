package com.crisil.covenant.service;

import java.sql.SQLException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Re-runs a whole transaction that SQL Server chose as a deadlock victim. Applying covenants takes
 * Quartz's lock rows inside the business transaction, and parallel applies (several RMs onboarding at
 * once, or the scheduler's own threads) can still deadlock on them. SQL Server then rolls back the
 * entire transaction, Quartz rows included, so running it again is safe.
 *
 * <p>Quartz retries its own lock statement after the rollback, so the deadlock usually surfaces later as
 * a foreign-key error on {@code fk_schedule_applied} (the schedule's covenant row was rolled back). That
 * error is treated as a deadlock too. Call {@link #run} from outside the {@code @Transactional} method so
 * each attempt is a fresh transaction.
 */
@Component
public class TransientRetry {

    private static final Logger LOG = LoggerFactory.getLogger(TransientRetry.class);
    static final int MAX_ATTEMPTS = 4;
    private static final int SQLSERVER_DEADLOCK = 1205;

    private final long backoffMillis;

    public TransientRetry() {
        this(50);
    }

    TransientRetry(long backoffMillis) {
        this.backoffMillis = backoffMillis;
    }

    public <T> T run(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException e) {
                if (attempt >= MAX_ATTEMPTS || !isDeadlock(e)) {
                    throw e;
                }
                LOG.warn("Transaction lost a deadlock (attempt {} of {}); retrying", attempt, MAX_ATTEMPTS);
                pause(attempt);
            }
        }
    }

    static boolean isDeadlock(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof PessimisticLockingFailureException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getErrorCode() == SQLSERVER_DEADLOCK) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.contains("fk_schedule_applied")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(backoffMillis * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
