package com.crisil.covenant.util;

import com.crisil.covenant.formula.FormulaType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class FormulaTypeConverter extends LowerCaseEnumConverter<FormulaType> {
    public FormulaTypeConverter() {
        super(FormulaType.class);
    }
}
