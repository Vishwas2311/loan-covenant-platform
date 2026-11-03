package com.crisil.covenant.util;

import com.crisil.covenant.enums.CovenantConditionType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CovenantConditionTypeConverter extends LowerCaseEnumConverter<CovenantConditionType> {
    public CovenantConditionTypeConverter() {
        super(CovenantConditionType.class);
    }
}
