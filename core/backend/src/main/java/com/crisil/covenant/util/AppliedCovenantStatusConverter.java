package com.crisil.covenant.util;

import com.crisil.covenant.enums.AppliedCovenantStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AppliedCovenantStatusConverter extends LowerCaseEnumConverter<AppliedCovenantStatus> {
    public AppliedCovenantStatusConverter() {
        super(AppliedCovenantStatus.class);
    }
}
