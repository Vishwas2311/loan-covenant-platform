package com.crisil.covenant.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.entity.AppliedCovenant;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.crisil.covenant.enums.ScheduleStatus;

class MonitoringScheduleTest {

    @Test
    void settersAndGettersWork() {
        MonitoringSchedule s = new MonitoringSchedule();
        AppliedCovenant a = new AppliedCovenant();
        s.setAppliedCovenant(a);
        s.setQuartzJobKey("assessment-1");
        s.setCronExpression("0 0 2 * * ?");
        Instant t = Instant.parse("2026-06-04T10:00:00Z");
        s.setNextRunAt(t);
        s.setLastRunAt(t.plusSeconds(60));
        s.setStatus(ScheduleStatus.PAUSED);

        assertThat(s.getAppliedCovenant()).isSameAs(a);
        assertThat(s.getQuartzJobKey()).isEqualTo("assessment-1");
        assertThat(s.getCronExpression()).isEqualTo("0 0 2 * * ?");
        assertThat(s.getNextRunAt()).isEqualTo(t);
        assertThat(s.getLastRunAt()).isEqualTo(t.plusSeconds(60));
        assertThat(s.getStatus()).isEqualTo(ScheduleStatus.PAUSED);
        assertThat(s.getId()).isNull();
    }

    @Test
    void scheduleStatusEnumHasExpectedValues() {
        assertThat(ScheduleStatus.values()).contains(ScheduleStatus.SCHEDULED, ScheduleStatus.PAUSED);
    }
}
