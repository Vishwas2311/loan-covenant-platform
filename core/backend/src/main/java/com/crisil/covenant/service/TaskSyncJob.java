package com.crisil.covenant.service;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Keeps every CP's tasks current in the background (see {@link WorkQueueService#syncAll()}), so bank-wide
 * roles read their task lists without a sync of thousands of CPs, and tasks on CPs nobody opens are still
 * opened and escalated. Clustered Quartz runs it on one node at a time; registered by
 * {@link DailyRunInitializer}.
 *
 * <p>Not {@code @Transactional}: the sync uses one transaction per CP.
 */
@DisallowConcurrentExecution
public class TaskSyncJob extends QuartzJobBean {

    private static final Logger LOG = LoggerFactory.getLogger(TaskSyncJob.class);

    @Autowired
    private WorkQueueService workQueueService;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        try {
            int synced = workQueueService.syncAll();
            if (synced > 0) {
                LOG.info("Background task sync: {} CPs synced", synced);
            }
        } catch (RuntimeException ex) {
            LOG.error("Background task sync failed: {}", ex.getClass().getSimpleName());
        }
    }
}
