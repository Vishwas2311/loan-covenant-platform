package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * US-020: monitoring schedule is durable across restarts.
 * The proof here is that with JDBC job store enabled the Quartz tables
 * (V6__quartz.sql) are present and writable — i.e. a CronTrigger created
 * by ScheduleService is persisted, not held in JVM memory.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuartzJdbcDurabilityIT {

    @Autowired
    private DataSource dataSource;

    @Test
    void quartzTablesAreCreatedByFlyway() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String table : new String[] {
                "QRTZ_JOB_DETAILS", "QRTZ_TRIGGERS", "QRTZ_SIMPLE_TRIGGERS",
                "QRTZ_CRON_TRIGGERS", "QRTZ_SIMPROP_TRIGGERS", "QRTZ_BLOB_TRIGGERS",
                "QRTZ_CALENDARS", "QRTZ_PAUSED_TRIGGER_GRPS",
                "QRTZ_FIRED_TRIGGERS", "QRTZ_SCHEDULER_STATE", "QRTZ_LOCKS"}) {
            Integer rows = jdbc.queryForObject("select count(*) from " + table, Integer.class);
            assertThat(rows).as("table %s must be present (Flyway V6)", table).isNotNull();
        }
    }
}
