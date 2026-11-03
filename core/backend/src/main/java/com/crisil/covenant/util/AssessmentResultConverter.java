package com.crisil.covenant.util;

import com.crisil.covenant.enums.AssessmentResult;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AssessmentResultConverter extends LowerCaseEnumConverter<AssessmentResult> {
    public AssessmentResultConverter() {
        super(AssessmentResult.class);
    }
}
