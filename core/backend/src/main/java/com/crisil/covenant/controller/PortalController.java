package com.crisil.covenant.controller;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.service.PortalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** C1: the borrower's own endpoints (BORROWER role only; see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/portal")
@Tag(name = "Borrower portal", description = "C1: what a borrower sees and sends")
public class PortalController {

    private final PortalService service;

    public PortalController(PortalService service) {
        this.service = service;
    }

    private static String user() {
        return CurrentUserContext.currentUser().orElse(null);
    }

    @GetMapping("/cps")
    @Operation(summary = "The CPs the bank shared with the signed-in borrower")
    public List<PortalService.PortalCp> cps() {
        return service.cpsFor(user());
    }

    @GetMapping("/cp/{cpId}")
    @Operation(summary = "What is due on one of the borrower's CPs, and what they sent")
    public PortalService.PortalView view(@PathVariable String cpId) {
        return service.view(user(), cpId);
    }

    @PostMapping(value = "/cp/{cpId}/certificate", consumes = "multipart/form-data")
    @Operation(summary = "Send a signed compliance certificate for one period")
    public PortalService.PortalView certificate(
            @PathVariable String cpId, @RequestParam("file") MultipartFile file,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam String signedBy, @RequestParam(defaultValue = "false") boolean confirm) {
        return service.submitCertificate(user(), cpId, file, periodEnd, signedBy, confirm);
    }

    @PostMapping(value = "/cp/{cpId}/statement", consumes = "multipart/form-data")
    @Operation(summary = "Send a financial statement; the bank's document service reads it")
    public PortalService.PortalView statement(
            @PathVariable String cpId, @RequestParam("file") MultipartFile file,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd) {
        return service.submitStatement(user(), cpId, file, periodEnd);
    }
}
