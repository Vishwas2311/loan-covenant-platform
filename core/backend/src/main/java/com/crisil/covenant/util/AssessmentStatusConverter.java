package com.crisil.covenant.util;

import com.crisil.covenant.enums.AssessmentStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AssessmentStatusConverter extends LowerCaseEnumConverter<AssessmentStatus> {
    public AssessmentStatusConverter() {
        super(AssessmentStatus.class);
    }
}
