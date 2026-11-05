package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.entity.CovenantMaster;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import com.crisil.covenant.entity.MonitoringSchedule;
import com.crisil.covenant.enums.ScheduleStatus;
import com.crisil.covenant.repository.MonitoringScheduleRepository;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock private Scheduler scheduler;
    @Mock private MonitoringScheduleRepository repository;
    @InjectMocks private ScheduleService service;

    @Test
    void createScheduleAddsJobAndPersistsRow() throws Exception {
        AppliedCovenant applied = newApplied();
        when(repository.saveAndFlush(any(MonitoringSchedule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MonitoringSchedule schedule = service.createSchedule(applied);

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        verify(scheduler).addJob(jobCaptor.capture(), org.mockito.ArgumentMatchers.eq(true));
        verify(scheduler).scheduleJob(any(Trigger.class));
        // useProperties=true requires String job-data values; a Long would make the real
        // scheduler throw and surface as SCHEDULE_FAILED, so the id must be stored as a String.
        assertThat(jobCaptor.getValue().getJobDataMap().get(AssessmentJob.APPLIED_ID_KEY))
                .isEqualTo("1");
        assertThat(schedule.getQuartzJobKey()).isEqualTo("assessment-1");
        assertThat(schedule.getCronExpression()).isEqualTo(Frequency.DAILY.defaultCron());
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SCHEDULED);
    }

    @Test
    void createScheduleWrapsSchedulerException() throws Exception {
        AppliedCovenant applied = newApplied();
        lenient().doThrow(new SchedulerException("boom")).when(scheduler)
                .scheduleJob(any(Trigger.class));
        assertThatThrownBy(() -> service.createSchedule(applied))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Failed to schedule");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createScheduleWrapsInvalidCron() {
        AppliedCovenant applied = newApplied();
        applied.setCronExpression("invalid");
        assertThatThrownBy(() -> service.createSchedule(applied))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rescheduleRebuildsTrigger() throws Exception {
        MonitoringSchedule schedule = baseSchedule();
        when(repository.saveAndFlush(any(MonitoringSchedule.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(scheduler.rescheduleJob(any(TriggerKey.class), any(Trigger.class)))
                .thenReturn(new Date());

        service.reschedule(schedule, "0 0 3 * * ?");

        verify(scheduler).rescheduleJob(any(TriggerKey.class), any(Trigger.class));
        assertThat(schedule.getCronExpression()).isEqualTo("0 0 3 * * ?");
    }

    @Test
    void rescheduleRejectsInvalidCron() {
        MonitoringSchedule schedule = baseSchedule();
        assertThatThrownBy(() -> service.reschedule(schedule, "not-a-cron"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid cron");
    }

    @Test
    void rescheduleHandlesSchedulerException() throws Exception {
        MonitoringSchedule schedule = baseSchedule();
        when(scheduler.rescheduleJob(any(TriggerKey.class), any(Trigger.class)))
                .thenThrow(new SchedulerException("boom"));
        assertThatThrownBy(() -> service.reschedule(schedule, "0 0 3 * * ?"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Failed to reschedule");
    }

    @Test
    void pauseAndResume() throws Exception {
        MonitoringSchedule schedule = baseSchedule();
        when(repository.saveAndFlush(any(MonitoringSchedule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.pause(schedule);
        verify(scheduler).pauseJob(any(JobKey.class));
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.PAUSED);

        service.resume(schedule);
        verify(scheduler).resumeJob(any(JobKey.class));
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SCHEDULED);
    }

    @Test
    void pauseHandlesSchedulerException() throws Exception {
        MonitoringSchedule schedule = baseSchedule();
        org.mockito.Mockito.doThrow(new SchedulerException("boom")).when(scheduler)
                .pauseJob(any(JobKey.class));
        assertThatThrownBy(() -> service.pause(schedule))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("pause");
    }

    @Test
    void resumeHandlesSchedulerException() throws Exception {
        MonitoringSchedule schedule = baseSchedule();
        org.mockito.Mockito.doThrow(new SchedulerException("boom")).when(scheduler)
                .resumeJob(any(JobKey.class));
        assertThatThrownBy(() -> service.resume(schedule))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("resume");
    }

    @Test
    void isScheduledChecksRepository() {
        when(repository.findByAppliedCovenantId("1")).thenReturn(Optional.of(baseSchedule()));
        assertThat(service.isScheduled("1")).isTrue();
        when(repository.findByAppliedCovenantId("2")).thenReturn(Optional.empty());
        assertThat(service.isScheduled("2")).isFalse();
    }

    @Test
    void isValidCronUtility() {
        assertThat(ScheduleService.isValidCron("0 0 * * * ?")).isTrue();
        assertThat(ScheduleService.isValidCron("nonsense")).isFalse();
    }

    @Test
    void invalidCronExceptionReportedByCreateSchedule() throws Exception {
        AppliedCovenant applied = newApplied();
        applied.setCronExpression("totally not cron");
        assertThatThrownBy(() -> service.createSchedule(applied))
                .isInstanceOf(ApiException.class);
        verify(scheduler, never()).scheduleJob(any(Trigger.class));
        verify(scheduler, times(0)).addJob(any(JobDetail.class), any(Boolean.class));
    }

    @Test
    void registerDailyNotificationRunSchedulesDurableJobWhenTriggerAbsent() throws Exception {
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);

        service.registerDailyNotificationRun("0 0 6 * * ?");

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        verify(scheduler).addJob(jobCaptor.capture(), org.mockito.ArgumentMatchers.eq(true));
        JobDetail job = jobCaptor.getValue();
        assertThat(job.getKey().getName()).isEqualTo(ScheduleService.DAILY_RUN_KEY);
        assertThat(job.getKey().getGroup()).isEqualTo(ScheduleService.JOB_GROUP);
        assertThat(job.isDurable()).isTrue();
        assertThat(job.getJobClass()).isEqualTo(DailyBreachRunJob.class);
        // First boot: no trigger yet → schedule a fresh one, don't reschedule.
        verify(scheduler).scheduleJob(any(Trigger.class));
        verify(scheduler, never()).rescheduleJob(any(TriggerKey.class), any(Trigger.class));
    }

    @Test
    void registerDailyNotificationRunReschedulesInPlaceWhenTriggerExists() throws Exception {
        // Restart: the durable trigger is already in the JDBC store, so registration must be
        // idempotent — reschedule in place rather than scheduleJob (which would throw
        // ObjectAlreadyExistsException on the real scheduler and duplicate the trigger).
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(true);

        service.registerDailyNotificationRun("0 0 6 * * ?");

        verify(scheduler).addJob(any(JobDetail.class), org.mockito.ArgumentMatchers.eq(true));
        verify(scheduler).rescheduleJob(any(TriggerKey.class), any(Trigger.class));
        verify(scheduler, never()).scheduleJob(any(Trigger.class));
    }

    @Test
    void registerDailyNotificationRunRejectsInvalidCron() throws Exception {
        assertThatThrownBy(() -> service.registerDailyNotificationRun("nonsense"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid notifications cron");
        verify(scheduler, never()).addJob(any(JobDetail.class), any(Boolean.class));
        verify(scheduler, never()).scheduleJob(any(Trigger.class));
    }

    @Test
    void registerDailyNotificationRunWrapsSchedulerException() throws Exception {
        org.mockito.Mockito.doThrow(new SchedulerException("boom")).when(scheduler)
                .addJob(any(JobDetail.class), org.mockito.ArgumentMatchers.eq(true));
        assertThatThrownBy(() -> service.registerDailyNotificationRun("0 0 6 * * ?"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Failed to register");
    }

    @Test
    void defaultCronsAreSpreadOverTheHourAndOtherCronsAreKept() {
        String daily = ScheduleService.spread("0 0 2 * * ?", "ac-1");
        assertThat(daily).endsWith(" 2 * * ?").isEqualTo(ScheduleService.spread("0 0 2 * * ?", "ac-1"));
        assertThat(org.quartz.CronExpression.isValidExpression(daily)).isTrue();
        assertThat(ScheduleService.spread("0 0 2 1 1,4,7,10 ?", "ac-1")).endsWith(" 2 1 1,4,7,10 ?");
        assertThat(ScheduleService.spread("0 30 5 * * ?", "ac-1")).isEqualTo("0 30 5 * * ?");
        assertThat(ScheduleService.spread("0 0/5 * * * ?", "ac-1")).isEqualTo("0 0/5 * * * ?");
        assertThat(ScheduleService.spread("bad", "ac-1")).isEqualTo("bad");
        long distinct = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(i -> ScheduleService.spread("0 0 2 * * ?", "covenant-" + i)).distinct().count();
        assertThat(distinct).isGreaterThan(800); // 1,000 covenants no longer share one second
    }

    @Test
    void realignRebuildsBunchedOrForeignZoneTriggersOnly() throws Exception {
        TriggerKey old = new TriggerKey("assessment-1", ScheduleService.TRIGGER_GROUP);
        TriggerKey done = new TriggerKey("assessment-2", ScheduleService.TRIGGER_GROUP);
        TriggerKey daily = new TriggerKey(ScheduleService.DAILY_RUN_KEY, ScheduleService.TRIGGER_GROUP);
        when(scheduler.getTriggerKeys(any())).thenReturn(java.util.Set.of(old, done, daily));
        when(scheduler.getTrigger(old)).thenReturn(cron(old, "0 0 2 * * ?", "Etc/UTC"));
        when(scheduler.getTrigger(done)).thenReturn(cron(done, ScheduleService.spread("0 0 2 * * ?", "2"),
                java.util.TimeZone.getDefault().getID()));

        assertThat(service.realignCovenantTriggers()).isEqualTo(1);

        ArgumentCaptor<Trigger> fresh = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).rescheduleJob(org.mockito.ArgumentMatchers.eq(old), fresh.capture());
        org.quartz.CronTrigger rebuilt = (org.quartz.CronTrigger) fresh.getValue();
        assertThat(rebuilt.getCronExpression()).isEqualTo(ScheduleService.spread("0 0 2 * * ?", "1"));
        assertThat(rebuilt.getTimeZone()).isEqualTo(java.util.TimeZone.getDefault());

        when(scheduler.getTriggerKeys(any())).thenThrow(new SchedulerException("store down"));
        assertThat(service.realignCovenantTriggers()).isZero();
    }

    private static org.quartz.CronTrigger cron(TriggerKey key, String expr, String zone) {
        return org.quartz.TriggerBuilder.newTrigger().withIdentity(key).forJob(key.getName(), ScheduleService.JOB_GROUP)
                .withSchedule(org.quartz.CronScheduleBuilder.cronSchedule(expr)
                        .inTimeZone(java.util.TimeZone.getTimeZone(zone)))
                .build();
    }

    @Test
    void registerTaskSyncRunSchedulesTheBackgroundTaskSyncJob() throws Exception {
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);

        service.registerTaskSyncRun("0 0/5 * * * ?");

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        verify(scheduler).addJob(jobCaptor.capture(), org.mockito.ArgumentMatchers.eq(true));
        assertThat(jobCaptor.getValue().getKey().getName()).isEqualTo(ScheduleService.TASK_SYNC_RUN_KEY);
        assertThat(jobCaptor.getValue().getJobClass()).isEqualTo(TaskSyncJob.class);
        verify(scheduler).scheduleJob(any(Trigger.class));
    }

    @Test
    void registerIngestionPreStageRunSchedulesDurableJobWhenTriggerAbsent() throws Exception {
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);

        service.registerIngestionPreStageRun("0 30 5 * * ?");

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        verify(scheduler).addJob(jobCaptor.capture(), org.mockito.ArgumentMatchers.eq(true));
        JobDetail job = jobCaptor.getValue();
        assertThat(job.getKey().getName()).isEqualTo(ScheduleService.PRE_STAGE_RUN_KEY);
        assertThat(job.getKey().getGroup()).isEqualTo(ScheduleService.JOB_GROUP);
        assertThat(job.isDurable()).isTrue();
        assertThat(job.getJobClass()).isEqualTo(IngestionPreStageJob.class);
        // First boot: no trigger yet → schedule a fresh one, don't reschedule.
        verify(scheduler).scheduleJob(any(Trigger.class));
        verify(scheduler, never()).rescheduleJob(any(TriggerKey.class), any(Trigger.class));
    }

    @Test
    void registerIngestionPreStageRunReschedulesInPlaceWhenTriggerExists() throws Exception {
        // Restart idempotency: the durable trigger already exists in the JDBC store, so
        // reschedule in place rather than scheduleJob (which would duplicate/throw).
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(true);

        service.registerIngestionPreStageRun("0 30 5 * * ?");

        verify(scheduler).addJob(any(JobDetail.class), org.mockito.ArgumentMatchers.eq(true));
        verify(scheduler).rescheduleJob(any(TriggerKey.class), any(Trigger.class));
        verify(scheduler, never()).scheduleJob(any(Trigger.class));
    }

    @Test
    void registerIngestionPreStageRunRejectsInvalidCron() throws Exception {
        assertThatThrownBy(() -> service.registerIngestionPreStageRun("nonsense"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid pre-stage cron");
        verify(scheduler, never()).addJob(any(JobDetail.class), any(Boolean.class));
        verify(scheduler, never()).scheduleJob(any(Trigger.class));
    }

    @Test
    void registerIngestionPreStageRunWrapsSchedulerException() throws Exception {
        org.mockito.Mockito.doThrow(new SchedulerException("boom")).when(scheduler)
                .addJob(any(JobDetail.class), org.mockito.ArgumentMatchers.eq(true));
        assertThatThrownBy(() -> service.registerIngestionPreStageRun("0 30 5 * * ?"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Failed to register");
    }

    private static AppliedCovenant newApplied() {
        CovenantMaster master = new CovenantMaster();
        setId(master, "7");
        master.setName("M");
        master.setCategory(CovenantCategory.FINANCIAL);
        master.setConditionExpression("DSCR > 1");
        master.setBusinessSegments(List.of());
        master.setActive(true);

        AppliedCovenant a = new AppliedCovenant();
        setId(a, "1");
        a.setCpId("CP-1");
        a.setCovenantMaster(master);
        a.setFrequency(Frequency.DAILY);
        a.setCronExpression(Frequency.DAILY.defaultCron());
        a.setStartDate(LocalDate.now());
        a.setSnapshotJson("{}");
        return a;
    }

    private MonitoringSchedule baseSchedule() {
        MonitoringSchedule s = new MonitoringSchedule();
        s.setAppliedCovenant(newApplied());
        s.setQuartzJobKey("assessment-1");
        s.setCronExpression(Frequency.DAILY.defaultCron());
        s.setStatus(ScheduleStatus.SCHEDULED);
        return s;
    }

    private static void setId(Object target, String id) {
        try {
            Field f = target.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
