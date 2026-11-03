package com.crisil.covenant.util;

import com.crisil.covenant.enums.CovenantClassification;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CovenantClassificationConverter extends LowerCaseEnumConverter<CovenantClassification> {
    public CovenantClassificationConverter() {
        super(CovenantClassification.class);
    }
}
