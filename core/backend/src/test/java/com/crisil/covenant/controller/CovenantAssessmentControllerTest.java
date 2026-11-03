package com.crisil.covenant.controller;

import com.crisil.covenant.security.CpAccessGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.service.AiSuggestionService;
import com.crisil.covenant.dto.AiStatusRecommendation;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.AssessmentType;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.dto.AttachmentView;
import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.dto.FileDownloadResult;
import com.crisil.covenant.dto.SetDispositionRequest;
import com.crisil.covenant.dto.StatusAuditView;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.enums.CovenantStatusSource;
import com.crisil.covenant.service.AttachmentService;
import com.crisil.covenant.service.ReviewService;

/** US-MON-16/17/18/19: the CP assessment controller delegates each endpoint to its service. */
@ExtendWith(MockitoExtension.class)
class CovenantAssessmentControllerTest {

    @Mock private ReviewService service;
    @Mock private AttachmentService attachmentService;
    @Mock private AiSuggestionService aiSuggestionService;
    @Mock private CpAccessGuard guard;
    @InjectMocks private CovenantAssessmentController controller;

    private static final List<MultipartFile> FILES =
            List.of(new MockMultipartFile("files", "e.pdf", "application/pdf", "d".getBytes()));

    @Test
    void reviewDelegates() {
        List<CpReviewRow> rows = List.of(row("1"));
        when(service.reviewRows("CP-1")).thenReturn(rows);
        assertThat(controller.review("CP-1")).isSameAs(rows);
        verify(service).reviewRows("CP-1");
    }

    @Test
    void setStatusDelegates() {
        CpReviewRow row = row("1");
        SetDispositionRequest req =
                new SetDispositionRequest(CovenantDispositionStatus.WAIVER, null, null, null);
        when(service.setDisposition(eq("CP-1"), eq("1"), any())).thenReturn(row);
        assertThat(controller.setStatus("CP-1", "1", req)).isSameAs(row);
        verify(service).setDisposition("CP-1", "1", req);
    }

    @Test
    void decideStatusDelegates() {
        CpReviewRow row = row("1");
        com.crisil.covenant.dto.DispositionDecisionRequest req = new com.crisil.covenant.dto.DispositionDecisionRequest(
                com.crisil.covenant.dto.DispositionDecisionRequest.Decision.APPROVE, "ok");
        when(service.decideDisposition("CP-1", "1", req)).thenReturn(row);
        assertThat(controller.decideStatus("CP-1", "1", req)).isSameAs(row);
    }

    @Test
    void statusAuditForCpDelegates() {
        List<StatusAuditView> trail = List.of();
        when(service.auditForCp("CP-1")).thenReturn(trail);
        assertThat(controller.statusAuditForCp("CP-1")).isSameAs(trail);
        verify(service).auditForCp("CP-1");
    }

    @Test
    void statusAuditForCovenantDelegates() {
        List<StatusAuditView> trail = List.of();
        when(service.auditForCovenant("1")).thenReturn(trail);
        assertThat(controller.statusAuditForCovenant("1")).isSameAs(trail);
        verify(service).auditForCovenant("1");
    }

    @Test
    void addAttachmentsDelegates() {
        List<AttachmentView> views = List.of();
        when(attachmentService.addAttachments("CP-1", "1", FILES)).thenReturn(views);
        assertThat(controller.addAttachments("CP-1", "1", FILES)).isSameAs(views);
        verify(attachmentService).addAttachments("CP-1", "1", FILES);
    }

    @Test
    void removeAttachmentDelegates() {
        List<AttachmentView> views = List.of();
        when(attachmentService.removeAttachment("CP-1", "1", "5")).thenReturn(views);
        assertThat(controller.removeAttachment("CP-1", "1", "5")).isSameAs(views);
        verify(attachmentService).removeAttachment("CP-1", "1", "5");
    }

    @Test
    void complianceCertificatesDelegates() {
        List<AttachmentView> views = List.of();
        when(attachmentService.certificatesForCp("CP-1")).thenReturn(views);
        assertThat(controller.complianceCertificates("CP-1", null)).isSameAs(views);
        verify(attachmentService).certificatesForCp("CP-1");
    }

    @Test
    void complianceCertificatesFilterByPeriod() {
        java.time.LocalDate q1 = java.time.LocalDate.of(2026, 3, 31);
        AttachmentView march = new AttachmentView("1", "f1", "q1.pdf", "u", java.time.Instant.now(), q1);
        AttachmentView general = new AttachmentView("2", "f2", "g.pdf", "u", java.time.Instant.now());
        when(attachmentService.certificatesForCp("CP-1")).thenReturn(List.of(march, general));
        assertThat(controller.complianceCertificates("CP-1", q1)).containsExactly(march);
    }

    @Test
    void addComplianceCertificatesDelegates() {
        List<AttachmentView> views = List.of();
        java.time.LocalDate q1 = java.time.LocalDate.of(2026, 3, 31);
        when(attachmentService.addCertificates("CP-1", FILES, q1)).thenReturn(views);
        assertThat(controller.addComplianceCertificates("CP-1", FILES, q1)).isSameAs(views);
        verify(attachmentService).addCertificates("CP-1", FILES, q1);
    }

    @Test
    void removeComplianceCertificateDelegates() {
        List<AttachmentView> views = List.of();
        when(attachmentService.removeCertificate("CP-1", "7")).thenReturn(views);
        assertThat(controller.removeComplianceCertificate("CP-1", "7")).isSameAs(views);
        verify(attachmentService).removeCertificate("CP-1", "7");
    }

    @Test
    void aiSuggestionsDelegates() {
        List<AiStatusRecommendation> recs = List.of();
        when(aiSuggestionService.suggestForCp("CP-1")).thenReturn(recs);
        assertThat(controller.aiSuggestions("CP-1")).isSameAs(recs);
        verify(aiSuggestionService).suggestForCp("CP-1");
    }

    // ---- US-MON-23 downloads: delegate to the service and stream the bytes as an attachment ----

    @Test
    void downloadAttachmentDelegatesAndStreamsBytes() {
        byte[] bytes = "PDFDATA".getBytes(StandardCharsets.UTF_8);
        when(attachmentService.downloadAttachment("CP-1", "1", "5"))
                .thenReturn(new FileDownloadResult(bytes, "application/pdf", "evidence.pdf"));

        ResponseEntity<Resource> resp = controller.downloadAttachment("CP-1", "1", "5");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(resp.getHeaders().getContentDisposition().getFilename()).isEqualTo("evidence.pdf");
        assertThat(((ByteArrayResource) resp.getBody()).getByteArray()).isEqualTo(bytes);
        verify(attachmentService).downloadAttachment("CP-1", "1", "5");
    }

    @Test
    void downloadComplianceCertificateDelegatesAndStreamsBytes() {
        byte[] bytes = "CERT".getBytes(StandardCharsets.UTF_8);
        when(attachmentService.downloadCertificate("CP-1", "7"))
                .thenReturn(new FileDownloadResult(bytes, "application/pdf", "cert.pdf"));

        ResponseEntity<Resource> resp = controller.downloadComplianceCertificate("CP-1", "7");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentDisposition().getFilename()).isEqualTo("cert.pdf");
        assertThat(((ByteArrayResource) resp.getBody()).getByteArray()).isEqualTo(bytes);
        verify(attachmentService).downloadCertificate("CP-1", "7");
    }

    @Test
    void downloadFallsBackToOctetStreamAndDefaultNameWhenHeadersMissing() {
        when(attachmentService.downloadAttachment("CP-1", "1", "5"))
                .thenReturn(new FileDownloadResult(null, null, ""));

        ResponseEntity<Resource> resp = controller.downloadAttachment("CP-1", "1", "5");

        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(resp.getHeaders().getContentDisposition().getFilename()).isEqualTo("download");
        assertThat(((ByteArrayResource) resp.getBody()).getByteArray()).isEmpty();
    }

    @Test
    void downloadFallsBackWhenContentTypeBlankAndNameNull() {
        when(attachmentService.downloadAttachment("CP-1", "1", "5"))
                .thenReturn(new FileDownloadResult("x".getBytes(StandardCharsets.UTF_8), "   ", null));

        ResponseEntity<Resource> resp = controller.downloadAttachment("CP-1", "1", "5");

        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(resp.getHeaders().getContentDisposition().getFilename()).isEqualTo("download");
    }

    @Test
    void downloadFallsBackToOctetStreamOnUnparseableContentType() {
        when(attachmentService.downloadAttachment("CP-1", "1", "5"))
                .thenReturn(new FileDownloadResult("x".getBytes(StandardCharsets.UTF_8),
                        "invalid-no-slash", "evidence.pdf"));

        ResponseEntity<Resource> resp = controller.downloadAttachment("CP-1", "1", "5");

        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(resp.getHeaders().getContentDisposition().getFilename()).isEqualTo("evidence.pdf");
    }

    private static CpReviewRow row(String appliedId) {
        return new CpReviewRow(appliedId, "50", "Cov-" + appliedId, "DSCR > 1.2",
                CovenantCategory.FINANCIAL, AssessmentType.AUTO, "1.0", "Double", null,
                AssessmentResult.BREACHED, "100", Instant.now(),
                CovenantDispositionStatus.BREACHED, null, CovenantStatusSource.SYSTEM, null, true,
                LocalDate.now(), LocalDate.now(), null, 0L, List.of());
    }
}
