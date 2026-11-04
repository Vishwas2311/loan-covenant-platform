package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.Assessment;
import com.crisil.covenant.entity.CertificateRequirement;
import com.crisil.covenant.entity.CovenantDisposition;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The work queue re-syncs a CP only when it changed, was never synced here, or its sync is stale. */
class TaskSyncTrackerTest {

    private static final Instant T0 = Instant.parse("2026-11-04T09:00:00Z");
    private final TaskSyncTracker tracker = new TaskSyncTracker(Duration.ofMinutes(2));
    private final CpChangeListener listener = new CpChangeListener();

    @BeforeEach
    void setUp() {
        tracker.register();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void syncsWhenNewChangedOrStale() {
        assertThat(tracker.needsSync("CP-1", T0)).as("never synced").isTrue();
        tracker.startSync("CP-1");
        tracker.synced("CP-1", T0);
        assertThat(tracker.needsSync("CP-1", T0.plusSeconds(60))).isFalse();
        assertThat(tracker.needsSync("CP-1", T0.plusSeconds(121))).as("stale").isTrue();
        tracker.markChanged("CP-1");
        assertThat(tracker.needsSync("CP-1", T0.plusSeconds(1))).as("changed").isTrue();
        tracker.startSync("CP-1");
        assertThat(tracker.needsSync("CP-1", T0.plusSeconds(1))).isFalse();
        assertThat(tracker.lock("CP-1")).isSameAs(tracker.lock("CP-1"));
    }

    @Test
    void entityWritesMarkTheirCpOnlyAfterCommit() throws Exception {
        AppliedCovenant ac = new AppliedCovenant();
        ac.setCpId("CP-7");
        set(ac, "id", "ac-7");
        Assessment a = new Assessment();
        a.setAppliedCovenant(ac);
        CovenantDisposition d = new CovenantDisposition();
        d.setCpId("CP-8");
        CertificateRequirement r = new CertificateRequirement("CP-9", java.time.LocalDate.of(2026, 9, 30),
                java.time.LocalDate.of(2026, 10, 31), null);

        TransactionSynchronizationManager.initSynchronization();
        listener.changed(a);
        listener.changed(d);
        assertThat(tracker.takeChangedAppliedCovenants()).as("not before commit").isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        TransactionSynchronizationManager.clearSynchronization();

        assertThat(tracker.takeChangedAppliedCovenants()).containsExactly("ac-7");
        assertThat(tracker.takeChangedAppliedCovenants()).as("drained").isEmpty();
        tracker.synced("CP-8", T0);
        assertThat(tracker.needsSync("CP-8", T0)).isTrue();

        listener.changed(ac);
        listener.changed(r);
        listener.changed("not an entity we track");
        tracker.synced("CP-7", T0);
        tracker.synced("CP-9", T0);
        assertThat(tracker.needsSync("CP-7", T0)).isTrue();
        assertThat(tracker.needsSync("CP-9", T0)).isTrue();
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }
}
