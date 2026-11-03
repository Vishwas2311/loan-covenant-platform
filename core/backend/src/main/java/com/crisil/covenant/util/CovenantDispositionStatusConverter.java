package com.crisil.covenant.util;

import com.crisil.covenant.enums.CovenantDispositionStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CovenantDispositionStatusConverter extends LowerCaseEnumConverter<CovenantDispositionStatus> {
    public CovenantDispositionStatusConverter() {
        super(CovenantDispositionStatus.class);
    }
}
