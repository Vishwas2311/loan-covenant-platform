package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.service.AssessmentService;
import com.crisil.covenant.enums.AssessmentTrigger;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.Trigger;
import com.crisil.covenant.entity.MonitoringSchedule;
import com.crisil.covenant.repository.MonitoringScheduleRepository;

class AssessmentJobTest {

    @Test
    void executeRunsAssessmentAndUpdatesSchedule() throws Exception {
        AssessmentService assessmentService = mock(AssessmentService.class);
        MonitoringScheduleRepository scheduleRepo = mock(MonitoringScheduleRepository.class);

        AssessmentJob job = new AssessmentJob();
        injectField(job, "assessmentService", assessmentService);
        injectField(job, "scheduleRepo", scheduleRepo);

        JobExecutionContext ctx = mock(JobExecutionContext.class);
        JobDataMap dataMap = new JobDataMap();
        // useProperties=true stores job data as Strings, so the job reads + parses a String.
        dataMap.put(AssessmentJob.APPLIED_ID_KEY, String.valueOf(42L));
        when(ctx.getMergedJobDataMap()).thenReturn(dataMap);
        Date nextFire = new Date();
        when(ctx.getNextFireTime()).thenReturn(nextFire);

        MonitoringSchedule schedule = new MonitoringSchedule();
        when(scheduleRepo.findByAppliedCovenantId("42")).thenReturn(Optional.of(schedule));

        job.executeInternal(ctx);

        verify(assessmentService).runAssessment(eq("42"), eq(AssessmentTrigger.SCHEDULED), eq(null));
        verify(scheduleRepo).save(schedule);
        assertThat(schedule.getLastRunAt()).isNotNull();
        assertThat(schedule.getNextRunAt()).isEqualTo(nextFire.toInstant());
    }

    @Test
    void executeHandlesAssessmentFailure() throws Exception {
        AssessmentService assessmentService = mock(AssessmentService.class);
        MonitoringScheduleRepository scheduleRepo = mock(MonitoringScheduleRepository.class);
        when(assessmentService.runAssessment(any(), any(), any()))
                .thenThrow(new RuntimeException("upstream"));

        AssessmentJob job = new AssessmentJob();
        injectField(job, "assessmentService", assessmentService);
        injectField(job, "scheduleRepo", scheduleRepo);

        JobExecutionContext ctx = mock(JobExecutionContext.class);
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(AssessmentJob.APPLIED_ID_KEY, String.valueOf(1L));
        when(ctx.getMergedJobDataMap()).thenReturn(dataMap);
        when(ctx.getNextFireTime()).thenReturn(null);
        when(scheduleRepo.findByAppliedCovenantId("1")).thenReturn(Optional.empty());

        job.executeInternal(ctx);  // should not throw
    }

    @Test
    void executeWithNoScheduleRowDoesNothingExtra() throws Exception {
        AssessmentService assessmentService = mock(AssessmentService.class);
        MonitoringScheduleRepository scheduleRepo = mock(MonitoringScheduleRepository.class);
        when(scheduleRepo.findByAppliedCovenantId(any())).thenReturn(Optional.empty());

        AssessmentJob job = new AssessmentJob();
        injectField(job, "assessmentService", assessmentService);
        injectField(job, "scheduleRepo", scheduleRepo);

        JobExecutionContext ctx = mock(JobExecutionContext.class);
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(AssessmentJob.APPLIED_ID_KEY, String.valueOf(9L));
        when(ctx.getMergedJobDataMap()).thenReturn(dataMap);
        Trigger trigger = mock(Trigger.class);
        when(ctx.getTrigger()).thenReturn(trigger);

        job.executeInternal(ctx);

        verify(scheduleRepo, org.mockito.Mockito.never()).save(any());
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
