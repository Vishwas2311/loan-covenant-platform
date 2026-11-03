package com.crisil.covenant.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** C4: a partial change to a task. Any field left null is kept; {@code done=true} closes it. */
public record UpdateWorkTaskRequest(
        @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._@-]+") String assignee,
        LocalDate dueDate,
        @Size(max = 1000) String note,
        Boolean done) {
}
