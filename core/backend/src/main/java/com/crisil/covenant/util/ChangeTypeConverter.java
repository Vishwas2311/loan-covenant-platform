package com.crisil.covenant.util;

import com.crisil.covenant.entity.AppliedCovenantAudit;
import jakarta.persistence.Converter;

/**
 * Persists {@link AppliedCovenantAudit.ChangeType} as its lower-cased name so it satisfies the
 * {@code ck_applied_audit_type CHECK (change_type IN ('frequency','cron','status'))} constraint in
 * {@code V1__baseline.sql}, and reads it back case-insensitively. This brings the audit enum in line
 * with the rest of the schema, whose enum-domain CHECKs are all lower case (see
 * {@link LowerCaseEnumConverter}).
 */
@Converter(autoApply = true)
public class ChangeTypeConverter extends LowerCaseEnumConverter<AppliedCovenantAudit.ChangeType> {
    public ChangeTypeConverter() {
        super(AppliedCovenantAudit.ChangeType.class);
    }
}
