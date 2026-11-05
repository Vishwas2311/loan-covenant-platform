package com.crisil.covenant.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Which CPs' work-queue tasks may be out of date, so reading the task list only re-syncs those instead
 * of every CP on every read (load test: 3.2 s p50 with 20 users when every read re-synced).
 *
 * <p>A CP needs a sync when it changed since its last sync on this node (a covenant, assessment,
 * schedule, disposition, certificate requirement or certificate was written, recorded after the
 * transaction commits by {@link CpChangeListener}), when this node never synced it, or when its last
 * sync is older than {@code covenant.tasks.resync-after}. The last rule covers changes made on another
 * Core node and checks that become overdue as the date moves on.
 */
@Component
public class TaskSyncTracker {

    private static volatile TaskSyncTracker current;

    private final Duration resyncAfter;
    private final Set<String> dirtyCps = ConcurrentHashMap.newKeySet();
    private final Set<String> dirtyApplied = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> lastSync = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public TaskSyncTracker(@Value("${covenant.tasks.resync-after:PT2M}") Duration resyncAfter) {
        this.resyncAfter = resyncAfter;
    }

    @PostConstruct
    @SuppressFBWarnings(value = "ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD",
            justification = "JPA instantiates CpChangeListener itself, so it reaches the one tracker bean through "
                    + "this static; it is set once when the singleton starts")
    void register() {
        current = this;
    }

    /** Called from JPA callbacks: marks the CP (or the applied covenant's CP) once the write commits. */
    static void changed(String cpId, String appliedCovenantId) {
        TaskSyncTracker tracker = current;
        if (tracker == null || (cpId == null && appliedCovenantId == null)) {
            return;
        }
        Runnable mark = () -> {
            if (cpId != null) {
                tracker.dirtyCps.add(cpId);
            }
            if (appliedCovenantId != null) {
                tracker.dirtyApplied.add(appliedCovenantId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mark.run();
                }
            });
        } else {
            mark.run();
        }
    }

    /** Applied covenants changed since the last call, to be resolved to their CPs by the caller. */
    Set<String> takeChangedAppliedCovenants() {
        Set<String> out = new HashSet<>();
        for (String id : dirtyApplied) {
            if (dirtyApplied.remove(id)) {
                out.add(id);
            }
        }
        return out;
    }

    public void markChanged(String cpId) {
        dirtyCps.add(cpId);
    }

    Duration resyncAfter() {
        return resyncAfter;
    }

    boolean needsSync(String cpId, Instant now) {
        return needsSync(cpId, now, resyncAfter);
    }

    /** As {@link #needsSync(String, Instant)}, with its own age limit (the background sync waits longer). */
    boolean needsSync(String cpId, Instant now, Duration staleAfter) {
        Instant last = lastSync.get(cpId);
        return last == null || dirtyCps.contains(cpId) || last.plus(staleAfter).isBefore(now);
    }

    /** Clears the CP's change mark before a sync, so a write during the sync marks it again. */
    void startSync(String cpId) {
        dirtyCps.remove(cpId);
    }

    void synced(String cpId, Instant at) {
        lastSync.put(cpId, at);
    }

    /** One lock per CP, so two reads on this node never open the same task twice. */
    Object lock(String cpId) {
        return locks.computeIfAbsent(cpId, k -> new Object());
    }
}
