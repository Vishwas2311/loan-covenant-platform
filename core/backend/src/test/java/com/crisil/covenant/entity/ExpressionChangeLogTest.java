package com.crisil.covenant.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExpressionChangeLogTest {

    @Test
    void noArgConstructorPresent() {
        ExpressionChangeLog log = new ExpressionChangeLog();
        assertThat(log.getId()).isNull();
        assertThat(log.getCovenantId()).isNull();
    }

    @Test
    void allArgsConstructorPopulatesFields() {
        Instant t = Instant.parse("2026-06-05T10:00:00Z");
        ExpressionChangeLog log = new ExpressionChangeLog(
                "77777777-7777-7777-7777-777777777777", "#x > 0", "#x > 1", t, "alice");
        assertThat(log.getCovenantId()).isEqualTo("77777777-7777-7777-7777-777777777777");
        assertThat(log.getOldExpression()).isEqualTo("#x > 0");
        assertThat(log.getNewExpression()).isEqualTo("#x > 1");
        assertThat(log.getChangedDate()).isEqualTo(t);
        assertThat(log.getChangedBy()).isEqualTo("alice");
    }
}
