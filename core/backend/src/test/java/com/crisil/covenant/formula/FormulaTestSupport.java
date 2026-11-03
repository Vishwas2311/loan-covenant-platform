package com.crisil.covenant.formula;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Shared in-memory {@link MeasureCatalog} for formula tests. */
final class FormulaTestSupport {

    private FormulaTestSupport() {
    }

    static MeasureCatalog catalog() {
        Map<String, MeasureMeta> map = new LinkedHashMap<>();
        map.put("DSCR", MeasureMeta.of("DSCR", FormulaType.NUMBER));
        map.put("PAT", MeasureMeta.of("PAT", FormulaType.NUMBER));
        map.put("EBITDA", MeasureMeta.of("EBITDA", FormulaType.NUMBER));
        map.put("ICR", MeasureMeta.of("ICR", FormulaType.NUMBER));
        map.put("ratio", new MeasureMeta("ratio", FormulaType.NUMBER, 0.6));
        map.put("auditor", MeasureMeta.of("auditor", FormulaType.STRING));
        map.put("lastAuditDate", MeasureMeta.of("lastAuditDate", FormulaType.DATE));
        map.put("insuranceActive", MeasureMeta.of("insuranceActive", FormulaType.BOOLEAN));
        return from(map);
    }

    static MeasureCatalog from(Map<String, MeasureMeta> map) {
        return new MeasureCatalog() {
            @Override
            public Optional<MeasureMeta> lookup(String code) {
                return Optional.ofNullable(map.get(code));
            }

            @Override
            public Collection<String> names() {
                return map.keySet();
            }
        };
    }
}
