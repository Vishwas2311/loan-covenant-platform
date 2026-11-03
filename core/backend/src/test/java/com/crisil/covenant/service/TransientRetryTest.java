package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

class TransientRetryTest {

    private final TransientRetry retry = new TransientRetry(0);

    @Test
    void retriesADeadlockVictimUntilItSucceeds() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.run(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException("wrapped", new SQLException("deadlock victim", "40001", 1205));
            }
            return "applied";
        });

        assertThat(result).isEqualTo("applied");
        assertThat(calls).hasValue(3);
    }

    @Test
    void treatsTheQuartzMaskedDeadlockAsRetryable() {
        assertThat(TransientRetry.isDeadlock(new DataIntegrityViolationException(
                "could not execute statement [conflicted with the FOREIGN KEY constraint \"fk_schedule_applied\"]")))
                .isTrue();
        assertThat(TransientRetry.isDeadlock(new CannotAcquireLockException("lock"))).isTrue();
    }

    @Test
    void otherErrorsAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(() -> {
            calls.incrementAndGet();
            throw new DataIntegrityViolationException("uk_applied_per_cp duplicate");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(calls).hasValue(1);
        assertThat(TransientRetry.isDeadlock(new RuntimeException((String) null))).isFalse();
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(() -> {
            calls.incrementAndGet();
            throw new CannotAcquireLockException("deadlock");
        })).isInstanceOf(CannotAcquireLockException.class);
        assertThat(calls).hasValue(TransientRetry.MAX_ATTEMPTS);
    }

    @Test
    void defaultConstructorBacksOffAndStillReturns() {
        AtomicInteger calls = new AtomicInteger();
        String result = new TransientRetry().run(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new CannotAcquireLockException("deadlock");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
    }
}
