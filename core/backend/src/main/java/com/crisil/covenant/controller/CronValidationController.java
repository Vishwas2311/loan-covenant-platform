package com.crisil.covenant.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.quartz.CronExpression;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schedules")
@Tag(name = "Schedules", description = "Cron validation + schedule utilities")
public class CronValidationController {

    @Schema(description = "A Quartz cron expression to validate")
    public record ValidateCronRequest(@NotBlank @Size(max = 64) String cronExpression) {}

    @Schema(description = "Validation outcome plus a preview of upcoming fire times")
    public record ValidateCronResponse(
            @Schema(description = "Whether the cron expression parsed successfully") boolean valid,
            @Schema(description = "Parse error message when invalid; null when valid") String error,
            @Schema(description = "Next fire times (up to 3) when valid") List<Instant> nextFireTimes) {}

    @PostMapping("/validate-cron")
    @Operation(summary = "Validate a Quartz cron expression and preview the next 3 fire times",
            description = "Parses a Quartz cron expression. When valid, returns the next three fire "
                    + "times; when invalid, returns valid=false with the parse error message.")
    public ValidateCronResponse validateCron(@Valid @RequestBody ValidateCronRequest request) {
        try {
            CronExpression expr = new CronExpression(request.cronExpression());
            List<Instant> next = new ArrayList<>();
            Date after = new Date();
            for (int i = 0; i < 3; i++) {
                Date fire = expr.getNextValidTimeAfter(after);
                if (fire == null) break;
                next.add(fire.toInstant());
                after = fire;
            }
            return new ValidateCronResponse(true, null, next);
        } catch (ParseException ex) {
            return new ValidateCronResponse(false, ex.getMessage(), List.of());
        }
    }
}
