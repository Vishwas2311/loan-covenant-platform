package com.crisil.covenant.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.config.IngestionProperties;
import com.crisil.covenant.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.http.HttpStatus;

class DailyRunInitializerTest {

    private static final String BREACH_CRON = "0 0 6 * * ?";
    private static final String PRE_STAGE_CRON = "0 30 5 * * ?";

    private IngestionProperties propsWithPreStageCron() {
        IngestionProperties props = new IngestionProperties();
        props.setPreStageCron(PRE_STAGE_CRON);
        return props;
    }

    @Test
    void registersBothBreachAndPreStageRunsAtStartup() {
        ScheduleService scheduleService = mock(ScheduleService.class);
        DailyRunInitializer initializer =
                new DailyRunInitializer(scheduleService, propsWithPreStageCron(), BREACH_CRON);

        initializer.run(mock(ApplicationArguments.class));

        verify(scheduleService).registerDailyNotificationRun(eq(BREACH_CRON));
        verify(scheduleService).registerIngestionPreStageRun(eq(PRE_STAGE_CRON));
        verify(scheduleService).registerTaskSyncRun(eq(DailyRunInitializer.DEFAULT_TASK_SYNC_CRON));
    }

    @Test
    void aFailedTaskSyncRegistrationDoesNotStopStartup() {
        ScheduleService scheduleService = mock(ScheduleService.class);
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRON", "bad"))
                .when(scheduleService).registerTaskSyncRun(eq("nonsense"));

        new DailyRunInitializer(scheduleService, propsWithPreStageCron(), BREACH_CRON, "nonsense")
                .run(mock(ApplicationArguments.class));

        verify(scheduleService).registerTaskSyncRun(eq("nonsense"));
    }

    @Test
    void preStageStillRegisteredWhenBreachRegistrationFails() {
        ScheduleService scheduleService = mock(ScheduleService.class);
        doThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SCHEDULE_FAILED", "boom"))
                .when(scheduleService).registerDailyNotificationRun(eq(BREACH_CRON));

        DailyRunInitializer initializer =
                new DailyRunInitializer(scheduleService, propsWithPreStageCron(), BREACH_CRON);

        initializer.run(mock(ApplicationArguments.class)); // must not throw

        // The breach failure is isolated: the pre-stage run is still registered.
        verify(scheduleService).registerIngestionPreStageRun(eq(PRE_STAGE_CRON));
    }

    @Test
    void breachRunUnaffectedWhenPreStageRegistrationFails() {
        ScheduleService scheduleService = mock(ScheduleService.class);
        doThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SCHEDULE_FAILED", "boom"))
                .when(scheduleService).registerIngestionPreStageRun(eq(PRE_STAGE_CRON));

        DailyRunInitializer initializer =
                new DailyRunInitializer(scheduleService, propsWithPreStageCron(), BREACH_CRON);

        initializer.run(mock(ApplicationArguments.class)); // must not throw

        verify(scheduleService).registerDailyNotificationRun(eq(BREACH_CRON));
    }

    @Test
    void constructorReadsPreStageCronFromIngestionProperties() {
        ScheduleService scheduleService = mock(ScheduleService.class);
        IngestionProperties props = new IngestionProperties();
        props.setPreStageCron("0 15 4 * * ?");

        DailyRunInitializer initializer =
                new DailyRunInitializer(scheduleService, props, BREACH_CRON);
        initializer.run(mock(ApplicationArguments.class));

        verify(scheduleService).registerIngestionPreStageRun(eq("0 15 4 * * ?"));
    }
}
