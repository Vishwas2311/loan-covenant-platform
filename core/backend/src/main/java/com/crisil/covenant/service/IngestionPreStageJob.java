package com.crisil.covenant.service;

import com.crisil.covenant.dto.PreStageResult;
import java.time.LocalDate;
import java.time.ZoneId;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * US-DS-13 (AC4): the pre-stage Quartz job. Fires ahead of the breach run (on its own cron / failure
 * domain) and delegates the whole due-driven sweep to {@link IngestionPreStageService}. Registered by
 * {@link DailyRunInitializer} via {@link ScheduleService#registerIngestionPreStageRun(String)}.
 *
 * <p>Not {@code @Transactional}: the delegate manages a transaction per gate decision so one entity's
 * failure cannot abort the sweep — wrapping it here would undo that isolation. A run-level failure is
 * logged and swallowed so a bad sweep never leaves the trigger in an error state.
 */
public class IngestionPreStageJob extends QuartzJobBean {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionPreStageJob.class);

    @Autowired
    private IngestionPreStageService preStageService;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        LOG.info("Running due-driven pre-stage ingestion sweep");
        try {
            PreStageResult result = preStageService.preStage(LocalDate.now(ZoneId.systemDefault()));
            String summary = ("pre-stage complete: candidates=" + result.candidateTargets()
                    + ", ingested=" + result.ingested() + ", skipped=" + result.skipped()
                    + ", failed=" + result.failed()).replace('\n', '_').replace('\r', '_');
            LOG.info("Pre-stage {}", summary);
        } catch (RuntimeException ex) {
            String safe = ex.getMessage() == null ? "null"
                    : ex.getMessage().replace('\n', '_').replace('\r', '_');
            LOG.error("Pre-stage sweep failed: {}", safe);
        }
    }
}
