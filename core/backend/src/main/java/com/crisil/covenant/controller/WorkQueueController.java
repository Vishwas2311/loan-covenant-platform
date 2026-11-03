package com.crisil.covenant.controller;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.UpdateWorkTaskRequest;
import com.crisil.covenant.dto.WorkTaskView;
import com.crisil.covenant.service.WorkQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Work queue", description = "C4: what each person has to do, by when")
public class WorkQueueController {

    private final WorkQueueService service;

    public WorkQueueController(WorkQueueService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Tasks on the caller's CPs, soonest due first",
            description = "scope=mine (default): the caller's tasks plus escalated ones on CPs they approve; "
                    + "scope=all: every task on their CPs. open=false also returns closed tasks.")
    public List<WorkTaskView> tasks(
            @Parameter(description = "mine or all") @RequestParam(defaultValue = "mine") String scope,
            @Parameter(description = "Only open tasks") @RequestParam(defaultValue = "true") boolean open) {
        return service.tasksFor(CurrentUserContext.currentUser().orElse(null), !"all".equals(scope), open);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Reassign, re-date, add a note to, or finish a task")
    public WorkTaskView update(@PathVariable String id, @Valid @RequestBody UpdateWorkTaskRequest request) {
        return service.update(id, request, CurrentUserContext.currentUser().orElse(null));
    }
}
