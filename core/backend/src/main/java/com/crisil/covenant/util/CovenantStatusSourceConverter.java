package com.crisil.covenant.util;

import com.crisil.covenant.enums.CovenantStatusSource;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CovenantStatusSourceConverter extends LowerCaseEnumConverter<CovenantStatusSource> {
    public CovenantStatusSourceConverter() {
        super(CovenantStatusSource.class);
    }
}
