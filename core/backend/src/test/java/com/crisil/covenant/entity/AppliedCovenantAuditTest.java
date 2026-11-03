package com.crisil.covenant.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AppliedCovenantAuditTest {

    @Test
    void noArgConstructorOk() {
        AppliedCovenantAudit a = new AppliedCovenantAudit();
        assertThat(a.getId()).isNull();
    }

    @Test
    void allArgsConstructorPopulatesFields() {
        Instant t = Instant.parse("2026-06-05T10:00:00Z");
        AppliedCovenantAudit a = new AppliedCovenantAudit(
                "11111111-1111-1111-1111-111111111111", AppliedCovenantAudit.ChangeType.FREQUENCY,
                "DAILY", "WEEKLY", t, "alice");
        assertThat(a.getAppliedCovenantId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(a.getChangeType()).isEqualTo(AppliedCovenantAudit.ChangeType.FREQUENCY);
        assertThat(a.getOldValue()).isEqualTo("DAILY");
        assertThat(a.getNewValue()).isEqualTo("WEEKLY");
        assertThat(a.getChangedDate()).isEqualTo(t);
        assertThat(a.getChangedBy()).isEqualTo("alice");
    }

    @Test
    void changeTypeEnumValues() {
        assertThat(AppliedCovenantAudit.ChangeType.values())
                .contains(AppliedCovenantAudit.ChangeType.FREQUENCY,
                          AppliedCovenantAudit.ChangeType.CRON,
                          AppliedCovenantAudit.ChangeType.STATUS);
    }
}
