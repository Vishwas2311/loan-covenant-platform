package com.crisil.covenant.util;

import com.crisil.covenant.enums.OpsAuditOutcome;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class OpsAuditOutcomeConverter extends LowerCaseEnumConverter<OpsAuditOutcome> {
    public OpsAuditOutcomeConverter() {
        super(OpsAuditOutcome.class);
    }
}
