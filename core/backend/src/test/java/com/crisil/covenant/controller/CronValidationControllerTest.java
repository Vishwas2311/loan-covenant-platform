package com.crisil.covenant.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CronValidationControllerTest {

    private final CronValidationController controller = new CronValidationController();

    @Test
    void validCronReturnsThreeNextFireTimes() {
        var response = controller.validateCron(
                new CronValidationController.ValidateCronRequest("0 0 2 * * ?"));
        assertThat(response.valid()).isTrue();
        assertThat(response.error()).isNull();
        assertThat(response.nextFireTimes()).hasSize(3);
    }

    @Test
    void invalidCronReturnsErrorWithEmptyFireTimes() {
        var response = controller.validateCron(
                new CronValidationController.ValidateCronRequest("not-a-cron"));
        assertThat(response.valid()).isFalse();
        assertThat(response.error()).isNotBlank();
        assertThat(response.nextFireTimes()).isEmpty();
    }
}
