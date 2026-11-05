package com.crisil.covenant.service;

import com.crisil.covenant.config.IngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * At startup, registers the two singleton portfolio-wide Quartz runs with their configured crons:
 * <ul>
 *   <li>US-MON-14 — the daily breach run (default 06:00 daily);</li>
 *   <li>US-DS-13 — the due-driven pre-stage ingestion sweep (default 05:30 daily, ahead of the breach
 *       run so evaluation reads a fully staged copy).</li>
 *   <li>the background task sync (default every 5 minutes, {@link TaskSyncJob}).</li>
 * </ul>
 * Per-covenant schedules are still created on demand via {@link ScheduleService#createSchedule}. Both
 * registrations are idempotent (reschedule-in-place), so an application restart never duplicates a
 * trigger, and each runs in its own try/catch so one failing registration cannot block the other or
 * fail startup — the manual endpoints still work and the next restart retries.
 */
@Component
public class DailyRunInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DailyRunInitializer.class);
    static final String DEFAULT_TASK_SYNC_CRON = "0 0/5 * * * ?";

    private final ScheduleService scheduleService;
    private final String cron;
    private final String preStageCron;
    private final String taskSyncCron;

    public DailyRunInitializer(ScheduleService scheduleService, IngestionProperties ingestionProperties,
                               String cron) {
        this(scheduleService, ingestionProperties, cron, DEFAULT_TASK_SYNC_CRON);
    }

    @Autowired
    public DailyRunInitializer(ScheduleService scheduleService,
                               IngestionProperties ingestionProperties,
                               @Value("${covenant.notifications.cron:0 0 6 * * ?}") String cron,
                               @Value("${covenant.tasks.sync-cron:" + DEFAULT_TASK_SYNC_CRON + "}") String taskSyncCron) {
        this.scheduleService = scheduleService;
        this.cron = cron;
        this.preStageCron = ingestionProperties.getPreStageCron();
        this.taskSyncCron = taskSyncCron;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            scheduleService.registerDailyNotificationRun(cron);
        } catch (RuntimeException ex) {
            // Never fail startup because the daily trigger couldn't be registered: the manual
            // /notification-runs endpoint still works and the next restart retries registration.
            LOG.error("Could not register the daily breach-run trigger at startup", ex);
        }
        try {
            scheduleService.registerIngestionPreStageRun(preStageCron);
        } catch (RuntimeException ex) {
            // Pre-staging is an optimisation: if its trigger can't be registered, evaluation still
            // works (JIT ingestion falls back to on-demand gating). Log and continue.
            LOG.error("Could not register the pre-stage ingestion trigger at startup", ex);
        }
        try {
            scheduleService.registerTaskSyncRun(taskSyncCron);
        } catch (RuntimeException ex) {
            // Reads by RMs and approvers still sync their own CPs; only the background catch-up is missing.
            LOG.error("Could not register the background task-sync trigger at startup", ex);
        }
    }
}
