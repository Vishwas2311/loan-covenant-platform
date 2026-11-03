package com.crisil.covenant.service;

import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.formula.FormulaType;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.formula.MeasureMeta;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.repository.MeasureRepository;

/**
 * Bridges persisted {@link Measure}s into the {@link MeasureCatalog} the formula engine validates
 * and evaluates against. The catalog is a point-in-time snapshot of the active measures.
 */
@Service
public class MeasureCatalogService {

    private final MeasureRepository repository;

    public MeasureCatalogService(MeasureRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public MeasureCatalog activeCatalog() {
        Map<String, MeasureMeta> byCode = new LinkedHashMap<>();
        for (Measure measure : repository.findByActiveTrueOrderByCodeAsc()) {
            byCode.put(measure.getCode(),
                    MeasureMeta.of(measure.getCode(), toFormulaType(measure.getDataType())));
        }
        return new SnapshotCatalog(byCode);
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
