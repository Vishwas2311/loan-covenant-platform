package com.crisil.covenant.util;

import jakarta.persistence.AttributeConverter;
import java.util.Locale;

/**
 * Base JPA converter that persists an enum as its lower-cased {@link Enum#name()} and reads it back
 * case-insensitively. The consolidated schema ({@code all_tables.sql}) defines every enum-domain
 * {@code CHECK} constraint in lower case, while the Java enum constants stay upper case (so JSON API
 * payloads are unaffected). Concrete subclasses bind a specific enum type and are annotated
 * {@code @Converter(autoApply = true)} so the mapping applies wherever that enum is persisted.
 *
 * @param <E> the enum type handled by the concrete subclass
 */
public abstract class LowerCaseEnumConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

    private final Class<E> type;

    protected LowerCaseEnumConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public E convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Enum.valueOf(type, dbData.toUpperCase(Locale.ROOT));
    }
}
