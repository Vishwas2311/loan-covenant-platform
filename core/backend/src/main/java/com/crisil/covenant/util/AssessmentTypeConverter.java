package com.crisil.covenant.util;

import com.crisil.covenant.enums.AssessmentType;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AssessmentTypeConverter extends LowerCaseEnumConverter<AssessmentType> {
    public AssessmentTypeConverter() {
        super(AssessmentType.class);
    }
}
