package com.crisil.covenant.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.dto.PreStageResult;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;

class IngestionPreStageJobTest {

    @Test
    void executeInternalRunsPreStageSweepForToday() throws Exception {
        IngestionPreStageService service = mock(IngestionPreStageService.class);
        when(service.preStage(any())).thenReturn(new PreStageResult(5, 3, 1, 1));

        IngestionPreStageJob job = new IngestionPreStageJob();
        injectField(job, "preStageService", service);

        job.executeInternal(mock(JobExecutionContext.class));

        // Due-driven sweep runs for today in the system zone.
        verify(service).preStage(eq(LocalDate.now(ZoneId.systemDefault())));
    }

    @Test
    void executeInternalSwallowsSweepFailure() throws Exception {
        IngestionPreStageService service = mock(IngestionPreStageService.class);
        // CR/LF in the message exercises the sanitising error log without leaking a raw newline.
        when(service.preStage(any())).thenThrow(new RuntimeException("sweep\r\nboom"));

        IngestionPreStageJob job = new IngestionPreStageJob();
        injectField(job, "preStageService", service);

        job.executeInternal(mock(JobExecutionContext.class)); // must not throw
    }

    @Test
    void executeInternalSwallowsFailureWithNullMessage() throws Exception {
        IngestionPreStageService service = mock(IngestionPreStageService.class);
        when(service.preStage(any())).thenThrow(new RuntimeException());

        IngestionPreStageJob job = new IngestionPreStageJob();
        injectField(job, "preStageService", service);

        job.executeInternal(mock(JobExecutionContext.class)); // must not throw
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
