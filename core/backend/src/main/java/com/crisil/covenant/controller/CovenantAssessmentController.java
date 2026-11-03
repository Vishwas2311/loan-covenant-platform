package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.service.AiSuggestionService;
import com.crisil.covenant.dto.AiStatusRecommendation;
import com.crisil.covenant.dto.AttachmentView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.FileDownloadResult;
import com.crisil.covenant.dto.DispositionDecisionRequest;
import com.crisil.covenant.dto.SetDispositionRequest;
import com.crisil.covenant.dto.StatusAuditView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.crisil.covenant.service.AttachmentService;
import com.crisil.covenant.service.ReviewService;

/**
 * RM covenant-assessment workspace (Epic F): the CP assessment grid (US-MON-16), disposition
 * writes (US-MON-16/19) and the append-only status-audit trail (US-MON-19). Layered on top of the
 * monitoring read models; never mutates {@code cov_assessment}.
 */
@RestController
@RequestMapping("/api/v1/monitoring")
@Tag(name = "CP assessment", description = "RM covenant review, disposition & status audit")
public class CovenantAssessmentController {

    private final CpAccessGuard guard;

    private final ReviewService service;
    private final AttachmentService attachmentService;
    private final AiSuggestionService aiSuggestionService;

    public CovenantAssessmentController(ReviewService service, AttachmentService attachmentService,
                                        AiSuggestionService aiSuggestionService,
            CpAccessGuard guard) {
        this.guard = guard;
        this.service = service;
        this.attachmentService = attachmentService;
        this.aiSuggestionService = aiSuggestionService;
    }

    @GetMapping("/cp/{cpId}/review")
    @Operation(summary = "US-MON-16: assessment grid for a CP — one row per covenant with "
            + "expression, actual, headroom, derived status and read-only timing",
            description = "Returns the RM review grid: for each applied covenant, the effective "
                    + "expression, latest actual value, headroom, derived disposition status and "
                    + "read-only monitoring timing.")
    public List<CpReviewRow> review(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId) {
        guard.requireCp(cpId);
        return service.reviewRows(cpId);
    }

    @PutMapping("/cp/{cpId}/covenants/{appliedId}/status")
    @Operation(summary = "US-MON-16: record a Waiver/Extension disposition on a breached covenant "
            + "(EXTENSION requires a date); writes an append-only status-audit row (US-MON-19)",
            description = "Sets a Waiver or Extension disposition on a breached covenant. EXTENSION "
                    + "requires an extension date; the change is captured in the status-audit trail "
                    + "with its source (USER or AI).")
    public CpReviewRow setStatus(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Parameter(description = "Applied covenant id") @PathVariable String appliedId,
            @RequestBody SetDispositionRequest request) {
        guard.requireCp(cpId);
        return service.setDisposition(cpId, appliedId, request);
    }

    @PostMapping("/cp/{cpId}/covenants/{appliedId}/status/decision")
    @Operation(summary = "FR-12: approve or reject a requested Waiver/Extension (maker-checker)",
            description = "An APPROVER or ADMIN who did not raise the request approves or rejects it. "
                    + "Approval changes the covenant's effective status and is written to the status-audit "
                    + "trail; rejection leaves the breach standing.")
    public CpReviewRow decideStatus(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Parameter(description = "Applied covenant id") @PathVariable String appliedId,
            @Valid @RequestBody DispositionDecisionRequest request) {
        guard.requireCp(cpId);
        return service.decideDisposition(cpId, appliedId, request);
    }

    @GetMapping("/cp/{cpId}/status-audit")
    @Operation(summary = "US-MON-19: append-only status-audit trail for a CP, newest first")
    public List<StatusAuditView> statusAuditForCp(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId) {
        guard.requireCp(cpId);
        return service.auditForCp(cpId);
    }

    @GetMapping("/covenants/{appliedId}/status-audit")
    @Operation(summary = "US-MON-19: append-only status-audit trail for one applied covenant")
    public List<StatusAuditView> statusAuditForCovenant(
            @Parameter(description = "Applied covenant id") @PathVariable String appliedId) {
        guard.requireAppliedCovenant(appliedId);
        return service.auditForCovenant(appliedId);
    }

    @PostMapping(value = "/cp/{cpId}/covenants/{appliedId}/attachments",
            consumes = "multipart/form-data")
    @Operation(summary = "US-MON-17: upload one/more evidence files onto a covenant row via the "
            + "external file-upload service; returns the refreshed attachment list (newest first)",
            description = "Forwards each uploaded file to the external file-upload service and stores "
                    + "the returned fileId against the CP + covenant. Returns the refreshed list.")
    public List<AttachmentView> addAttachments(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Parameter(description = "Applied covenant id") @PathVariable String appliedId,
            @Parameter(description = "One or more evidence files") @RequestParam("files") List<MultipartFile> files) {
        guard.requireCp(cpId);
        return attachmentService.addAttachments(cpId, appliedId, files);
    }

    @DeleteMapping("/cp/{cpId}/covenants/{appliedId}/attachments/{attachmentId}")
    @Operation(summary = "US-MON-17: remove one evidence attachment from a covenant row")
    public List<AttachmentView> removeAttachment(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Parameter(description = "Applied covenant id") @PathVariable String appliedId,
            @Parameter(description = "Attachment id to remove") @PathVariable String attachmentId) {
        guard.requireCp(cpId);
        return attachmentService.removeAttachment(cpId, appliedId, attachmentId);
    }

    @GetMapping("/cp/{cpId}/covenants/{appliedId}/attachments/{attachmentId}/download")
    @Operation(summary = "US-MON-23: download one evidence attachment (bytes + original filename) "
            + "through the external file service; reused by the live grid and the cycle viewer",
            description = "Fetches the stored file back from the external file service, guarded to the "
                    + "owning CP + covenant, and streams it with a Content-Disposition attachment header "
                    + "carrying the original filename.")
    public ResponseEntity<Resource> downloadAttachment(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Parameter(description = "Applied covenant id") @PathVariable String appliedId,
            @Parameter(description = "Attachment id to download") @PathVariable String attachmentId) {
        guard.requireCp(cpId);
        return toDownloadResponse(attachmentService.downloadAttachment(cpId, appliedId, attachmentId));
    }

    @GetMapping("/cp/{cpId}/compliance-certificate")
    @Operation(summary = "US-MON-17: CP-level compliance certificates (newest first)")
    public List<AttachmentView> complianceCertificates(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Parameter(description = "FR-22: only certificates for this period end (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd) {
        guard.requireCp(cpId);
        List<AttachmentView> all = attachmentService.certificatesForCp(cpId);
        return periodEnd == null ? all : all.stream().filter(c -> periodEnd.equals(c.periodEnd())).toList();
    }

    @PostMapping(value = "/cp/{cpId}/compliance-certificate", consumes = "multipart/form-data")
    @Operation(summary = "US-MON-17: upload one/more CP-level compliance-certificate files via the "
            + "external file-upload service; returns the refreshed certificate list",
            description = "Uploads CP-level compliance certificates through the external file-upload "
                    + "service and stores the returned fileIds against the CP.")
    public List<AttachmentView> addComplianceCertificates(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Parameter(description = "One or more certificate files") @RequestParam("files") List<MultipartFile> files,
            @Parameter(description = "FR-22: the period end the certificate covers (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd) {
        guard.requireCp(cpId);
        return attachmentService.addCertificates(cpId, files, periodEnd);
    }

    @DeleteMapping("/cp/{cpId}/compliance-certificate/{certificateId}")
    @Operation(summary = "US-MON-17: remove one CP-level compliance certificate")
    public List<AttachmentView> removeComplianceCertificate(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Parameter(description = "Certificate id to remove") @PathVariable String certificateId) {
        guard.requireCp(cpId);
        return attachmentService.removeCertificate(cpId, certificateId);
    }

    @GetMapping("/cp/{cpId}/compliance-certificate/{certificateId}/download")
    @Operation(summary = "US-MON-23: download one CP-level compliance certificate (bytes + original "
            + "filename) through the external file service",
            description = "Fetches the stored certificate back from the external file service, guarded "
                    + "to its CP, and streams it with a Content-Disposition attachment header.")
    public ResponseEntity<Resource> downloadComplianceCertificate(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId,
            @Parameter(description = "Certificate id to download") @PathVariable String certificateId) {
        guard.requireCp(cpId);
        return toDownloadResponse(attachmentService.downloadCertificate(cpId, certificateId));
    }

    /**
     * Wrap a fetched file as a downloadable response: resolves the content-type (falling back to
     * {@code application/octet-stream}) and sets a {@code Content-Disposition: attachment} header whose
     * filename is safely encoded via {@link ContentDisposition} (no header injection).
     */
    private static ResponseEntity<Resource> toDownloadResponse(FileDownloadResult file) {
        MediaType type;
        try {
            type = file.contentType() == null || file.contentType().isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(file.contentType());
        } catch (RuntimeException ex) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        String name = file.fileName() == null || file.fileName().isBlank() ? "download" : file.fileName();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8)
                .build();
        byte[] content = file.content() == null ? new byte[0] : file.content();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new ByteArrayResource(content));
    }

    @PostMapping("/cp/{cpId}/ai-suggestions")
    @Operation(summary = "US-MON-18: per-covenant AI status recommendations for a CP (built from the "
            + "grid + uploaded fileIds); resilient — an unavailable AI service returns an empty list",
            description = "Calls the external AI service with the CP's covenant grid and uploaded "
                    + "fileIds to recommend a status per covenant. Resilient: on AI failure it returns "
                    + "an empty list rather than an error.")
    public List<AiStatusRecommendation> aiSuggestions(
            @Parameter(description = "Credit Proposal id (CP_ID)") @PathVariable String cpId) {
        guard.requireCp(cpId);
        return aiSuggestionService.suggestForCp(cpId);
    }
}
