package com.crisil.covenant.service;

import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.formula.FormulaType;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.formula.MeasureMeta;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.repository.MeasureRepository;

/**
 * Bridges persisted {@link Measure}s into the {@link MeasureCatalog} the formula engine validates
 * and evaluates against. The catalog is a point-in-time snapshot of the active measures.
 *
 * <p>Every assessment compiles its formula against the catalog, so the snapshot is kept for up to 30 seconds
 * instead of being read again each time (a daily run otherwise loads the measure table once per covenant).
 * A measure written through JPA drops the snapshot at once, and again when that transaction ends, so a
 * rolled-back change never lingers; another Core node sees a change within the 30 seconds.
 */
@Service
public class MeasureCatalogService {

    private static final long TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final AtomicLong CHANGES = new AtomicLong();

    private final MeasureRepository repository;
    private volatile Cached cached;

    public MeasureCatalogService(MeasureRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public MeasureCatalog activeCatalog() {
        long now = System.nanoTime();
        Cached c = cached;
        if (c != null && c.changes() == CHANGES.get() && now - c.loadedAt() < TTL_NANOS) {
            return c.catalog();
        }
        long changes = CHANGES.get();
        Map<String, MeasureMeta> byCode = new LinkedHashMap<>();
        for (Measure measure : repository.findByActiveTrueOrderByCodeAsc()) {
            byCode.put(measure.getCode(),
                    MeasureMeta.of(measure.getCode(), toFormulaType(measure.getDataType())));
        }
        MeasureCatalog catalog = new SnapshotCatalog(byCode);
        cached = new Cached(catalog, changes, now);
        return catalog;
    }

    /** A measure was written: drop the snapshot now and once more when the writing transaction ends. */
    static void measuresChanged() {
        CHANGES.incrementAndGet();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    CHANGES.incrementAndGet();
                }
            });
        }
    }

    private record Cached(MeasureCatalog catalog, long changes, long loadedAt) {
    }

    /** {@link DataColumnType} and {@link FormulaType} share their constant names. */
    static FormulaType toFormulaType(DataColumnType type) {
        return FormulaType.valueOf(type.name());
    }

    private record SnapshotCatalog(Map<String, MeasureMeta> byCode) implements MeasureCatalog {
        @Override
        public Optional<MeasureMeta> lookup(String code) {
            return Optional.ofNullable(byCode.get(code));
        }

        @Override
        public Collection<String> names() {
            return byCode.keySet();
        }
    }
}
