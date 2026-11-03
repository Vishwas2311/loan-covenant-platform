package com.crisil.covenant.service;

import com.crisil.covenant.service.AssessmentService;
import com.crisil.covenant.enums.AssessmentTrigger;
import java.time.Instant;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.crisil.covenant.repository.MonitoringScheduleRepository;

public class AssessmentJob extends QuartzJobBean {

    private static final Logger LOG = LoggerFactory.getLogger(AssessmentJob.class);
    public static final String APPLIED_ID_KEY = "appliedCovenantId";

    @Autowired
    private AssessmentService assessmentService;

    @Autowired
    private MonitoringScheduleRepository scheduleRepo;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void executeInternal(JobExecutionContext context) {
        JobDataMap data = context.getMergedJobDataMap();
        // The JDBC job store runs with useProperties=true, so job data round-trips as Strings;
        // the applied-covenant id is itself a String UUID, so read it directly.
        String appliedId = data.getString(APPLIED_ID_KEY);
        if (appliedId == null) {
            LOG.warn("Scheduled assessment fired without an applied-covenant id; skipping");
            return;
        }
        String safeId = appliedId.replace('\n', '_').replace('\r', '_');
        LOG.info("Running scheduled assessment for applied-covenant {}", safeId);
        try {
            assessmentService.runAssessment(appliedId, AssessmentTrigger.SCHEDULED, null);
        } catch (RuntimeException ex) {
            String safe = ex.getMessage() == null ? "null"
                    : ex.getMessage().replace('\n', '_').replace('\r', '_');
            LOG.error("Assessment job for applied-covenant {} failed: {}", safeId, safe);
        }
        scheduleRepo.findByAppliedCovenantId(appliedId).ifPresent(s -> {
            s.setLastRunAt(Instant.now());
            if (context.getNextFireTime() != null) {
                s.setNextRunAt(context.getNextFireTime().toInstant());
            }
            scheduleRepo.save(s);
        });
    }
}
