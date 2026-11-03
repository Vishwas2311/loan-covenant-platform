package com.crisil.covenant.util;

import com.crisil.covenant.formula.FunctionKind;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class FunctionKindConverter extends LowerCaseEnumConverter<FunctionKind> {
    public FunctionKindConverter() {
        super(FunctionKind.class);
    }
}
