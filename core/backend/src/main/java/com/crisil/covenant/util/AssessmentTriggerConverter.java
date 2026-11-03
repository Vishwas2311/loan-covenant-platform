package com.crisil.covenant.util;

import com.crisil.covenant.enums.AssessmentTrigger;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AssessmentTriggerConverter extends LowerCaseEnumConverter<AssessmentTrigger> {
    public AssessmentTriggerConverter() {
        super(AssessmentTrigger.class);
    }
}
