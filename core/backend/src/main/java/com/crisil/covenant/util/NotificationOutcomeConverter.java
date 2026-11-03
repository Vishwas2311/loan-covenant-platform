package com.crisil.covenant.util;

import com.crisil.covenant.entity.NotificationAudit;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class NotificationOutcomeConverter extends LowerCaseEnumConverter<NotificationAudit.Outcome> {
    public NotificationOutcomeConverter() {
        super(NotificationAudit.Outcome.class);
    }
}
