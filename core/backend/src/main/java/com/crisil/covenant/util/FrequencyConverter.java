package com.crisil.covenant.util;

import com.crisil.covenant.enums.Frequency;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class FrequencyConverter extends LowerCaseEnumConverter<Frequency> {
    public FrequencyConverter() {
        super(Frequency.class);
    }
}
