package com.crisil.covenant.controller;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.dto.BorrowerContactRequest;
import com.crisil.covenant.dto.ReminderRunResult;
import com.crisil.covenant.entity.BorrowerContact;
import com.crisil.covenant.entity.ReminderLog;
import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.service.ReminderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/monitoring")
@Tag(name = "Borrower reminders", description = "C2: who at the borrower is reminded about certificates")
public class BorrowerReminderController {

    private final ReminderService service;
    private final CpAccessGuard guard;

    public BorrowerReminderController(ReminderService service, CpAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/cp/{cpId}/borrower-contact")
    @Operation(summary = "The borrower contact for certificate reminders (204 when none is set)")
    public ResponseEntity<BorrowerContact> contact(@PathVariable String cpId) {
        guard.requireCp(cpId);
        return service.contact(cpId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/cp/{cpId}/borrower-contact")
    @Operation(summary = "Set the borrower contact and how many days before a due date to remind them")
    public BorrowerContact saveContact(@PathVariable String cpId, @Valid @RequestBody BorrowerContactRequest request) {
        guard.requireCp(cpId);
        return service.saveContact(cpId, request);
    }

    @GetMapping("/cp/{cpId}/reminders")
    @Operation(summary = "Every certificate reminder sent to this CP's borrower, newest first")
    public List<ReminderLog> reminders(@PathVariable String cpId) {
        guard.requireCp(cpId);
        return service.logFor(cpId);
    }

    @PostMapping("/reminder-runs")
    @Operation(summary = "Send today's borrower reminders now (also runs in the daily sweep)")
    public ReminderRunResult run() {
        return service.run(CurrentUserContext.currentUser().orElse("system"));
    }
}
