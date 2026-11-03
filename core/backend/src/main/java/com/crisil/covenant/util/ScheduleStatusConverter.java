package com.crisil.covenant.util;

import com.crisil.covenant.enums.ScheduleStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ScheduleStatusConverter extends LowerCaseEnumConverter<ScheduleStatus> {
    public ScheduleStatusConverter() {
        super(ScheduleStatus.class);
    }
}
