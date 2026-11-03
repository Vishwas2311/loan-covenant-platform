package com.crisil.covenant.util;

import com.crisil.covenant.enums.CovenantCategory;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CovenantCategoryConverter extends LowerCaseEnumConverter<CovenantCategory> {
    public CovenantCategoryConverter() {
        super(CovenantCategory.class);
    }
}
