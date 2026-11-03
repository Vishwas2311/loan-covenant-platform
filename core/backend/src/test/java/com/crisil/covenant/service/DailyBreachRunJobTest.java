package com.crisil.covenant.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.service.BreachNotificationService;
import com.crisil.covenant.dto.RunResult;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;

class DailyBreachRunJobTest {

    @Test
    void executeInternalRunsPortfolioSweepForTodayAsSystemUser() throws Exception {
        BreachNotificationService service = mock(BreachNotificationService.class);
        when(service.run(any(), any())).thenReturn(new RunResult("run-1", 3, 2, 2, 0));

        DailyBreachRunJob job = new DailyBreachRunJob();
        injectField(job, "breachNotificationService", service);

        job.executeInternal(mock(JobExecutionContext.class));

        // Portfolio sweep: no covenant id, run for today in the system zone, attributed to system.
        verify(service).run(eq(LocalDate.now(ZoneId.systemDefault())), eq(AuditingConfig.SYSTEM_USER));
    }

    @Test
    void executeInternalSwallowsRunFailure() throws Exception {
        BreachNotificationService service = mock(BreachNotificationService.class);
        // CR/LF in the message exercises the sanitising error log without leaking a raw newline.
        when(service.run(any(), any())).thenThrow(new RuntimeException("upstream\r\nboom"));

        DailyBreachRunJob job = new DailyBreachRunJob();
        injectField(job, "breachNotificationService", service);

        job.executeInternal(mock(JobExecutionContext.class)); // must not throw
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
