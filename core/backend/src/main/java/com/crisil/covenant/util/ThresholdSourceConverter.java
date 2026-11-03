package com.crisil.covenant.util;

import com.crisil.covenant.enums.ThresholdSource;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ThresholdSourceConverter extends LowerCaseEnumConverter<ThresholdSource> {
    public ThresholdSourceConverter() {
        super(ThresholdSource.class);
    }
}
