package com.crisil.covenant.dto;

import com.crisil.covenant.entity.WorkTask;
import java.time.Instant;
import java.time.LocalDate;

/**
 * C4: a work-queue item as the screen shows it. {@code escalated} = still open after its due date,
 * which also puts it in front of the CP's approver.
 */
public record WorkTaskView(
        String id, String cpId, String appliedCovenantId, String kind, String title, String assignee,
        LocalDate dueDate, String status, String note, Instant createdAt, Instant closedAt, String closedBy,
        boolean escalated) {

    public static WorkTaskView from(WorkTask t, LocalDate today) {
        return new WorkTaskView(t.getId(), t.getCpId(), t.getAppliedCovenantId(), t.getKind().name(), t.getTitle(),
                t.getAssignee(), t.getDueDate(), t.getStatus().name(), t.getNote(), t.getCreatedAt(), t.getClosedAt(),
                t.getClosedBy(), t.getStatus() == WorkTask.Status.OPEN && t.getDueDate().isBefore(today));
    }
}
