package com.crisil.covenant.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.springframework.test.util.ReflectionTestUtils;

class TaskSyncJobTest {

    @Test
    void runsTheBackgroundSyncAndNeverThrows() {
        WorkQueueService queue = mock(WorkQueueService.class);
        TaskSyncJob job = new TaskSyncJob();
        ReflectionTestUtils.setField(job, "workQueueService", queue);
        JobExecutionContext ctx = mock(JobExecutionContext.class);

        when(queue.syncAll()).thenReturn(3).thenReturn(0).thenThrow(new IllegalStateException("db down"));
        job.executeInternal(ctx);
        job.executeInternal(ctx);
        job.executeInternal(ctx);

        verify(queue, org.mockito.Mockito.times(3)).syncAll();
    }
}
