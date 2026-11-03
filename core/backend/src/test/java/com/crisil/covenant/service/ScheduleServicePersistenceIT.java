package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.enums.Frequency;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import com.crisil.covenant.entity.MonitoringSchedule;
import com.crisil.covenant.enums.ScheduleStatus;
import com.crisil.covenant.repository.MonitoringScheduleRepository;

/**
 * Regression IT for the SCHEDULE_FAILED bug that broke the onboarding wizard "Confirm" step.
 *
 * <p>The JDBC job store runs with {@code org.quartz.jobStore.useProperties=true}
 * (see application.yml), which requires every {@link org.quartz.JobDataMap} value to be a
 * String. {@link ScheduleService} must therefore store the applied-covenant id as a String;
 * a {@code Long} makes {@code scheduler.addJob(...)} throw a {@code JobPersistenceException}
 * that surfaced to the user as HTTP 500 {@code SCHEDULE_FAILED}.
 *
 * <p>The mock-based {@code ScheduleServiceTest} cannot catch this — a mocked {@code Scheduler}
 * never serialises the job data. This IT drives the real scheduler against H2 (MSSQL mode)
 * end-to-end, exactly as the wizard apply path does.
 */
@SpringBootTest
@ActiveProfiles("test")
class ScheduleServicePersistenceIT {

    @Autowired private ScheduleService scheduleService;
    @Autowired private Scheduler scheduler;
    @Autowired private CovenantMasterRepository masterRepo;
    @Autowired private AppliedCovenantRepository appliedRepo;
    @Autowired private MonitoringScheduleRepository scheduleRepo;

    @AfterEach
    void cleanup() throws Exception {
        for (JobKey key : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(ScheduleService.JOB_GROUP))) {
            scheduler.deleteJob(key);
        }
        scheduleRepo.deleteAll();
        appliedRepo.deleteAll();
        masterRepo.deleteAll();
    }

    @Test
    void createSchedulePersistsDurableJobWithStringJobData() throws Exception {
        AppliedCovenant applied = appliedRepo.saveAndFlush(newApplied("Sched IT DSCR"));

        MonitoringSchedule schedule = scheduleService.createSchedule(applied);

        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SCHEDULED);
        assertThat(schedule.getQuartzJobKey()).isEqualTo("assessment-" + applied.getId());

        // The job is durably persisted to the QRTZ_* tables (not just held in JVM memory)...
        JobDetail stored = scheduler.getJobDetail(
                new JobKey(schedule.getQuartzJobKey(), ScheduleService.JOB_GROUP));
        assertThat(stored).as("durable job must be persisted").isNotNull();

        // ...and its job data round-trips as a String (useProperties=true), so AssessmentJob can
        // read it back. The applied-covenant id is a UUID String that must survive verbatim.
        Object rawId = stored.getJobDataMap().get(AssessmentJob.APPLIED_ID_KEY);
        assertThat(rawId).isInstanceOf(String.class);
        assertThat((String) rawId).isEqualTo(applied.getId());
    }

    @Test
    void schedulesEveryCovenantInABulkApply() {
        // The wizard "Confirm" applies several covenants in one go — each must get its own
        // durable job without the batch failing.
        AppliedCovenant a1 = appliedRepo.saveAndFlush(newApplied("Sched IT DSCR A"));
        AppliedCovenant a2 = appliedRepo.saveAndFlush(newApplied("Sched IT DSCR B"));

        MonitoringSchedule s1 = scheduleService.createSchedule(a1);
        MonitoringSchedule s2 = scheduleService.createSchedule(a2);

        assertThat(s1.getQuartzJobKey()).isNotEqualTo(s2.getQuartzJobKey());
        assertThat(scheduleRepo.findByAppliedCovenantId(a1.getId())).isPresent();
        assertThat(scheduleRepo.findByAppliedCovenantId(a2.getId())).isPresent();
    }

    private AppliedCovenant newApplied(String masterName) {
        AppliedCovenant a = new AppliedCovenant();
        a.setCpId("CP-SCHED-IT");
        a.setCovenantMaster(newMaster(masterName));
        a.setFrequency(Frequency.DAILY);
        a.setCronExpression(Frequency.DAILY.defaultCron());
        a.setStartDate(LocalDate.now());
        a.setSnapshotJson("{}");
        return a;
    }

    private CovenantMaster newMaster(String name) {
        CovenantMaster cm = new CovenantMaster();
        cm.setName(name);
        cm.setCategory(CovenantCategory.FINANCIAL);
        cm.setConditionExpression("DSCR > 1");
        cm.setBusinessSegments(List.of());
        cm.setActive(true);
        return masterRepo.saveAndFlush(cm);
    }
}
