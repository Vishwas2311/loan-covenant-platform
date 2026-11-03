package com.crisil.covenant.formula;

import java.util.Collection;
import java.util.Optional;

/**
 * Supplies measure metadata to the formula engine. Implementations are provided by the
 * persistence layer (active measures) or by tests. Lookup is case-sensitive — measure codes are
 * exact identifiers.
 */
public interface MeasureCatalog {

    Optional<MeasureMeta> lookup(String measureCode);

    /** All known measure codes (used for "did you mean" suggestions and autocomplete). */
    Collection<String> names();
}
