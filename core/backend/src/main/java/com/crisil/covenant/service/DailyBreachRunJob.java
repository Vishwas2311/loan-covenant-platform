package com.crisil.covenant.service;

import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.service.BreachNotificationService;
import com.crisil.covenant.dto.RunResult;
import java.time.LocalDate;
import java.time.ZoneId;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * US-MON-14: the portfolio-wide daily sweep. Unlike {@link AssessmentJob} (one covenant per fire, keyed
 * by an {@code appliedCovenantId} in its JobDataMap), this fires once with no id and delegates the whole
 * portfolio to {@link BreachNotificationService}, which assesses every due covenant across all CPs and
 * sends the merged RM/WRM notifications. Registered by {@link DailyRunInitializer} via
 * {@link ScheduleService#registerDailyNotificationRun(String)}.
 *
 * <p>Not {@code @Transactional}: the delegate deliberately manages a transaction per unit of work so
 * one covenant's failure can't abort the batch — wrapping the whole sweep in one transaction here would
 * undo that isolation.
 */
public class DailyBreachRunJob extends QuartzJobBean {

    private static final Logger LOG = LoggerFactory.getLogger(DailyBreachRunJob.class);

    @Autowired
    private BreachNotificationService breachNotificationService;

    @Autowired
    private ReminderService reminderService;

    @Autowired
    private ScheduledReportService scheduledReportService;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        LOG.info("Running daily breach-assessment sweep");
        try {
            RunResult result = breachNotificationService.run(
                    LocalDate.now(ZoneId.systemDefault()), AuditingConfig.SYSTEM_USER);
            String summary = ("run " + result.runId() + " complete: assessed="
                    + result.covenantsAssessed() + ", cpsTouched=" + result.cpsTouched()
                    + ", sent=" + result.notificationsSent()
                    + ", failed=" + result.notificationsFailed())
                    .replace('\n', '_').replace('\r', '_');
            LOG.info("Daily breach {}", summary);
        } catch (RuntimeException ex) {
            String safe = ex.getMessage() == null ? "null"
                    : ex.getMessage().replace('\n', '_').replace('\r', '_');
            LOG.error("Daily breach run failed: {}", safe);
        }
        // C2 and C11 ride the same daily sweep; each is isolated so one failing cannot stop the other.
        try {
            reminderService.run(AuditingConfig.SYSTEM_USER);
        } catch (RuntimeException ex) {
            LOG.error("Daily borrower reminders failed: {}", ex.getClass().getSimpleName());
        }
        try {
            scheduledReportService.sendDue(AuditingConfig.SYSTEM_USER);
        } catch (RuntimeException ex) {
            LOG.error("Daily scheduled reports failed: {}", ex.getClass().getSimpleName());
        }
    }
}
