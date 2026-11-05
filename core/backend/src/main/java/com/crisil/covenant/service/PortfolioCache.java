package com.crisil.covenant.service;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Short-lived memory of portfolio aggregates (summaries, review rows), keyed by the CPs they cover.
 * Portfolio pages recompute the same aggregates on every view; with 250 users that alone saturated SQL
 * Server. A write that {@link CpChangeListener} sees (covenants, assessments, schedules, decisions,
 * certificates, evidence) drops only the entries that include that CP, at once and again when the writing
 * transaction ends; a write whose CP is unknown drops everything. A write made on another Core instance is
 * seen here within the time limit.
 */
final class PortfolioCache<K extends Collection<String>, V> {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    /** When each CP last changed, as a {@link #SEQUENCE} number. */
    private static final Map<String, Long> CP_CHANGED = new ConcurrentHashMap<>();
    /** When something changed whose CP is not known. */
    private static final AtomicLong ANY_CHANGED = new AtomicLong();
    private static final int MAX_TRACKED_CPS = 100_000;

    private final long ttlNanos;
    private final int maxEntries;
    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();

    PortfolioCache(Duration ttl, int maxEntries) {
        this.ttlNanos = ttl.toNanos();
        this.maxEntries = maxEntries;
    }

    /** The cached value for {@code key}, or a fresh one from {@code load} (then kept). */
    V get(K key, Supplier<V> load) {
        long now = System.nanoTime();
        Entry<V> e = entries.get(key);
        if (e != null && now - e.loadedAt() < ttlNanos && unchangedSince(key, e.sequence())) {
            return e.value();
        }
        long sequence = SEQUENCE.get();
        V value = load.get();
        if (entries.size() >= maxEntries) {
            entries.clear();
        }
        entries.put(key, new Entry<>(value, sequence, now));
        return value;
    }

    private static boolean unchangedSince(Collection<String> cps, long sequence) {
        if (ANY_CHANGED.get() > sequence) {
            return false;
        }
        for (String cp : cps) {
            Long changed = CP_CHANGED.get(cp);
            if (changed != null && changed > sequence) {
                return false;
            }
        }
        return true;
    }

    /** {@code cpId} (null: unknown) was written: drop its entries now and when the transaction ends. */
    static void changed(String cpId) {
        mark(cpId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    mark(cpId);
                }
            });
        }
    }

    private static void mark(String cpId) {
        long sequence = SEQUENCE.incrementAndGet();
        if (cpId == null) {
            ANY_CHANGED.set(sequence);
            return;
        }
        if (CP_CHANGED.size() >= MAX_TRACKED_CPS) {
            CP_CHANGED.clear();
            ANY_CHANGED.set(sequence);
        }
        CP_CHANGED.put(cpId, sequence);
    }

    private record Entry<V>(V value, long sequence, long loadedAt) {
    }
}
