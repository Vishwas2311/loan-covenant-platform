package com.crisil.covenant.service;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.exception.ApiException;
import java.text.ParseException;
import java.util.Date;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crisil.covenant.entity.MonitoringSchedule;
import com.crisil.covenant.enums.ScheduleStatus;
import com.crisil.covenant.repository.MonitoringScheduleRepository;

@Service
@Transactional
public class ScheduleService {

    public static final String JOB_GROUP = "covenant-assessments";
    public static final String TRIGGER_GROUP = "covenant-triggers";

    /** US-MON-14: shared key of the single portfolio-wide daily breach run (job + trigger). */
    public static final String DAILY_RUN_KEY = "daily-breach-run";

    /** US-DS-13: shared key of the single due-driven pre-stage ingestion sweep (job + trigger). */
    public static final String PRE_STAGE_RUN_KEY = "ingestion-pre-stage-run";

    /** Shared key of the single background task sync (job + trigger), see {@link TaskSyncJob}. */
    public static final String TASK_SYNC_RUN_KEY = "task-sync-run";

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleService.class);

    private final Scheduler scheduler;
    private final MonitoringScheduleRepository repository;

    public ScheduleService(Scheduler scheduler, MonitoringScheduleRepository repository) {
        this.scheduler = scheduler;
        this.repository = repository;
    }

    /** Quartz key prefix of the per-covenant assessment jobs and triggers. */
    static final String ASSESSMENT_KEY_PREFIX = "assessment-";

    /**
     * The cron a covenant's trigger fires on. A frequency's default cron fires at the top of an hour
     * ({@code 0 0 2 * * ?}); thousands of covenants on it all fired in the same second (review BUG-08: 4,800
     * at 02:00:00, a 17-minute backlog). Such a cron gets a second and minute within that hour, fixed per
     * covenant by its id, so the run spreads over the hour. Any other cron is kept as written.
     */
    static String spread(String cron, String appliedCovenantId) {
        String[] f = cron.trim().split("\\s+");
        if (f.length < 6 || !"0".equals(f[0]) || !"0".equals(f[1]) || !f[2].matches("\\d{1,2}")) {
            return cron;
        }
        int slot = Math.floorMod(appliedCovenantId.hashCode(), 3600);
        f[0] = String.valueOf(slot % 60);
        f[1] = String.valueOf(slot / 60);
        return String.join(" ", f);
    }

    private static CronScheduleBuilder covenantSchedule(String cron, String appliedCovenantId) {
        return CronScheduleBuilder.cronSchedule(spread(cron, appliedCovenantId))
                .inTimeZone(TimeZone.getDefault())
                .withMisfireHandlingInstructionFireAndProceed();
    }

    public MonitoringSchedule createSchedule(AppliedCovenant applied) {
        String jobKey = ASSESSMENT_KEY_PREFIX + applied.getId();
        JobDetail jobDetail = JobBuilder.newJob(AssessmentJob.class)
                .withIdentity(jobKey, JOB_GROUP)
                // The JDBC job store runs with useProperties=true (see application.yml), which
                // requires every JobDataMap value to be a String. Storing a Long here makes
                // scheduler.addJob() throw a JobPersistenceException, surfaced as SCHEDULE_FAILED.
                .usingJobData(AssessmentJob.APPLIED_ID_KEY, String.valueOf(applied.getId()))
                .storeDurably()
                .build();
        CronTrigger trigger;
        try {
            trigger = TriggerBuilder.newTrigger()
                    .withIdentity(jobKey, TRIGGER_GROUP)
                    .forJob(jobDetail)
                    .withSchedule(covenantSchedule(applied.getCronExpression(), String.valueOf(applied.getId())))
                    .build();
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRON",
                    "Failed to build trigger for cron: " + applied.getCronExpression());
        }
        try {
            scheduler.addJob(jobDetail, true);
            scheduler.scheduleJob(trigger);
        } catch (SchedulerException e) {
            // Log the Quartz cause that used to be swallowed. The cause is also chained into the
            // ApiException below; we keep the message argument-free so the CRLF-injection detector
            // (which flags entity-derived log arguments) stays satisfied.
            LOG.error("Failed to schedule assessment job", e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SCHEDULE_FAILED",
                    "Failed to schedule assessment job", e);
        }

        MonitoringSchedule schedule = new MonitoringSchedule();
        schedule.setAppliedCovenant(applied);
        schedule.setQuartzJobKey(jobKey);
        schedule.setCronExpression(applied.getCronExpression());
        schedule.setStatus(ScheduleStatus.SCHEDULED);
        Date next = trigger.getNextFireTime();
        if (next != null) {
            schedule.setNextRunAt(next.toInstant());
        }
        return repository.saveAndFlush(schedule);
    }

    /**
     * US-MON-14: register (or, on restart, re-point) the single portfolio-wide daily breach run as a
     * durable Quartz job + cron trigger. Reuses the same durable-job + fire-and-proceed idiom as
     * {@link #createSchedule}; keyed {@value #DAILY_RUN_KEY} in the shared job/trigger groups so a
     * second call (e.g. every application restart) reschedules in place rather than duplicating the
     * trigger or throwing {@code ObjectAlreadyExistsException}.
     */
    public void registerDailyNotificationRun(String cron) {
        registerDurableCronRun(DAILY_RUN_KEY, DailyBreachRunJob.class, cron,
                "Invalid notifications cron expression", "Failed to register daily breach-run job");
    }

    /**
     * US-DS-13 (AC4): register (or re-point on restart) the single due-driven pre-stage ingestion
     * sweep as a durable Quartz job + cron trigger. Uses the same durable-job + fire-and-proceed +
     * schedule-or-reschedule idiom as {@link #registerDailyNotificationRun} (idempotent across restarts)
     * but on its own key {@value #PRE_STAGE_RUN_KEY} so it runs on a separate trigger / failure domain
     * from the breach run.
     */
    public void registerIngestionPreStageRun(String cron) {
        registerDurableCronRun(PRE_STAGE_RUN_KEY, IngestionPreStageJob.class, cron,
                "Invalid pre-stage cron expression", "Failed to register pre-stage ingestion job");
    }

    /** Register (or re-point on restart) the background task sync, on its own key and trigger. */
    public void registerTaskSyncRun(String cron) {
        registerDurableCronRun(TASK_SYNC_RUN_KEY, TaskSyncJob.class, cron,
                "Invalid task-sync cron expression", "Failed to register task-sync job");
    }

    /**
     * Shared registrar for the singleton portfolio-wide cron jobs (breach run, pre-stage sweep). Durable
     * so the job survives with no trigger; then schedule-or-reschedule makes an application restart
     * idempotent (the trigger already exists in the JDBC store on the second boot) rather than throwing
     * {@code ObjectAlreadyExistsException} or duplicating the trigger.
     */
    private void registerDurableCronRun(String key, Class<? extends org.quartz.Job> jobClass,
                                        String cron, String invalidMessage, String failMessage) {
        if (!org.quartz.CronExpression.isValidExpression(cron)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRON", invalidMessage);
        }
        JobDetail jobDetail = JobBuilder.newJob(jobClass)
                .withIdentity(key, JOB_GROUP)
                .storeDurably()
                .build();
        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(key, TRIGGER_GROUP)
                .forJob(jobDetail)
                .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();
        try {
            scheduler.addJob(jobDetail, true);
            TriggerKey triggerKey = new TriggerKey(key, TRIGGER_GROUP);
            if (scheduler.checkExists(triggerKey)) {
                scheduler.rescheduleJob(triggerKey, trigger);
            } else {
                scheduler.scheduleJob(trigger);
            }
            LOG.info("Registered durable cron run");
        } catch (SchedulerException e) {
            LOG.error("Failed to register durable cron run", e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SCHEDULE_FAILED", failMessage, e);
        }
    }

    public void reschedule(MonitoringSchedule schedule, String newCron) {
        try {
            if (!org.quartz.CronExpression.isValidExpression(newCron)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRON",
                        "Invalid cron: " + newCron);
            }
            String key = schedule.getQuartzJobKey();
            String appliedId = key.startsWith(ASSESSMENT_KEY_PREFIX)
                    ? key.substring(ASSESSMENT_KEY_PREFIX.length()) : key;
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(key, TRIGGER_GROUP)
                    .forJob(key, JOB_GROUP)
                    .withSchedule(covenantSchedule(newCron, appliedId))
                    .build();
            scheduler.rescheduleJob(new TriggerKey(schedule.getQuartzJobKey(), TRIGGER_GROUP), trigger);
            schedule.setCronExpression(newCron);
            Date next = trigger.getNextFireTime();
            if (next != null) {
                schedule.setNextRunAt(next.toInstant());
            }
            repository.saveAndFlush(schedule);
        } catch (SchedulerException e) {
            LOG.error("Failed to reschedule monitoring job", e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "RESCHEDULE_FAILED",
                    "Failed to reschedule job");
        }
    }

    /**
     * Rebuilds per-covenant triggers made before {@link #spread} or in another time zone (review BUG-09:
     * 2,800 triggers created before the time-zone setting still fired on UTC). Idempotent: a trigger that is
     * already spread and in the server's zone is left alone. Returns how many were rebuilt.
     */
    public int realignCovenantTriggers() {
        String zone = TimeZone.getDefault().getID();
        int rebuilt = 0;
        try {
            for (TriggerKey key : scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(TRIGGER_GROUP))) {
                if (!key.getName().startsWith(ASSESSMENT_KEY_PREFIX)
                        || !(scheduler.getTrigger(key) instanceof CronTrigger current)) {
                    continue;
                }
                String appliedId = key.getName().substring(ASSESSMENT_KEY_PREFIX.length());
                String target = spread(current.getCronExpression(), appliedId);
                if (target.equals(current.getCronExpression()) && zone.equals(current.getTimeZone().getID())) {
                    continue;
                }
                CronTrigger fresh = TriggerBuilder.newTrigger()
                        .withIdentity(key)
                        .forJob(current.getJobKey())
                        .withSchedule(covenantSchedule(target, appliedId))
                        .build();
                scheduler.rescheduleJob(key, fresh);
                rebuilt++;
            }
        } catch (SchedulerException e) {
            LOG.error("Realigning covenant triggers stopped early", e);
        }
        return rebuilt;
    }

    public void pause(MonitoringSchedule schedule) {
        try {
            scheduler.pauseJob(new JobKey(schedule.getQuartzJobKey(), JOB_GROUP));
            schedule.setStatus(ScheduleStatus.PAUSED);
            repository.saveAndFlush(schedule);
        } catch (SchedulerException e) {
            LOG.error("Failed to pause monitoring job", e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PAUSE_FAILED",
                    "Failed to pause schedule");
        }
    }

    public void resume(MonitoringSchedule schedule) {
        try {
            scheduler.resumeJob(new JobKey(schedule.getQuartzJobKey(), JOB_GROUP));
            schedule.setStatus(ScheduleStatus.SCHEDULED);
            repository.saveAndFlush(schedule);
        } catch (SchedulerException e) {
            LOG.error("Failed to resume monitoring job", e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "RESUME_FAILED",
                    "Failed to resume schedule");
        }
    }

    public boolean isScheduled(String appliedCovenantId) {
        return repository.findByAppliedCovenantId(appliedCovenantId).isPresent();
    }

    public java.util.Optional<MonitoringSchedule> findByApplied(String appliedCovenantId) {
        return repository.findByAppliedCovenantId(appliedCovenantId);
    }

    static boolean isValidCron(String cron) {
        try {
            new org.quartz.CronExpression(cron);
            return true;
        } catch (ParseException e) {
            return false;
        }
    }
}
