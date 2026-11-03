package com.crisil.covenant.util;

import com.crisil.covenant.entity.ThresholdAudit;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ThresholdEventConverter extends LowerCaseEnumConverter<ThresholdAudit.Event> {
    public ThresholdEventConverter() {
        super(ThresholdAudit.Event.class);
    }
}
