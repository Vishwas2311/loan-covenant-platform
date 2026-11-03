package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.service.FileUploadClient;
import com.crisil.covenant.dto.FileDownloadResult;
import com.crisil.covenant.dto.FileUploadResult;
import com.crisil.covenant.dto.AttachmentView;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.crisil.covenant.entity.ComplianceCertificate;
import com.crisil.covenant.entity.CovenantAttachment;
import com.crisil.covenant.repository.ComplianceCertificateRepository;
import com.crisil.covenant.repository.CovenantAttachmentRepository;

/** US-MON-17: attachment/certificate upload validation, resilient forwarding and persistence. */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    @Mock private FileUploadClient fileUploadClient;
    @Mock private CovenantAttachmentRepository attachmentRepo;
    @Mock private ComplianceCertificateRepository certificateRepo;
    @Mock private AppliedCovenantRepository appliedRepo;

    private AttachmentService service() {
        return new AttachmentService(fileUploadClient, attachmentRepo, certificateRepo, appliedRepo);
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("files", "evidence.pdf", "application/pdf", "data".getBytes());
    }

    private static FileUploadResult ok(String fileId) {
        return new FileUploadResult(true, fileId, "stored.pdf", 200, null);
    }

    private static void assertStatus(Throwable t, int status) {
        assertThat(t).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void addAttachmentsUploadsAndPersists() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        when(fileUploadClient.upload(eq("evidence.pdf"), eq("application/pdf"), any())).thenReturn(ok("f1"));
        when(attachmentRepo.findByAppliedCovenantIdOrderByUploadedAtDesc("10"))
                .thenReturn(List.of(new CovenantAttachment("CP-1", "10", "f1", "stored.pdf", "u", Instant.now())));

        List<AttachmentView> out = service().addAttachments("CP-1", "10", List.of(pdf()));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).fileId()).isEqualTo("f1");
        verify(attachmentRepo).save(any(CovenantAttachment.class));
    }

    @Test
    void addAttachmentsRejectsUnknownCovenant() {
        when(appliedRepo.existsByIdAndCpId("99", "CP-1")).thenReturn(false);

        assertThatThrownBy(() -> service().addAttachments("CP-1", "99", List.of(pdf())))
                .satisfies(t -> assertStatus(t, 404));
        verify(fileUploadClient, never()).upload(anyString(), any(), any());
    }

    @Test
    void addAttachmentsRejectsNoFiles() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);

        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", List.of()))
                .satisfies(t -> assertStatus(t, 400));
    }

    @Test
    void addAttachmentsRejectsOversizeFile() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        MultipartFile big = org.mockito.Mockito.mock(MultipartFile.class);
        when(big.isEmpty()).thenReturn(false);
        when(big.getSize()).thenReturn(AttachmentService.MAX_FILE_BYTES + 1);

        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", List.of(big)))
                .satisfies(t -> assertStatus(t, 400));
        verify(fileUploadClient, never()).upload(anyString(), any(), any());
    }

    @Test
    void addAttachmentsRejectsUnsupportedType() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        MockMultipartFile exe = new MockMultipartFile("files", "malware.exe",
                "application/octet-stream", "x".getBytes());

        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", List.of(exe)))
                .satisfies(t -> assertStatus(t, 400));
        verify(fileUploadClient, never()).upload(anyString(), any(), any());
    }

    @Test
    void addAttachmentsSurfacesUploadFailureAsBadGateway() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        when(fileUploadClient.upload(anyString(), any(), any()))
                .thenReturn(new FileUploadResult(false, null, "evidence.pdf", 500, "boom"));

        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", List.of(pdf())))
                .satisfies(t -> assertStatus(t, 502));
        verify(attachmentRepo, never()).save(any());
    }

    @Test
    void addAttachmentsRejectsUnreadableFile() throws IOException {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        MultipartFile bad = org.mockito.Mockito.mock(MultipartFile.class);
        when(bad.isEmpty()).thenReturn(false);
        when(bad.getSize()).thenReturn(10L);
        when(bad.getOriginalFilename()).thenReturn("evidence.pdf");
        when(bad.getContentType()).thenReturn("application/pdf");
        when(bad.getBytes()).thenThrow(new IOException("disk"));

        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", List.of(bad)))
                .satisfies(t -> assertStatus(t, 400));
    }

    @Test
    void removeAttachmentDeletesWhenScoped() {
        CovenantAttachment att = new CovenantAttachment("CP-1", "10", "f1", "e.pdf", "u", Instant.now());
        when(attachmentRepo.findByIdAndCpIdAndAppliedCovenantId("5", "CP-1", "10")).thenReturn(Optional.of(att));
        when(attachmentRepo.findByAppliedCovenantIdOrderByUploadedAtDesc("10")).thenReturn(List.of());

        assertThat(service().removeAttachment("CP-1", "10", "5")).isEmpty();
        verify(attachmentRepo).delete(att);
    }

    @Test
    void removeAttachmentRejectsMissing() {
        when(attachmentRepo.findByIdAndCpIdAndAppliedCovenantId("5", "CP-1", "10")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().removeAttachment("CP-1", "10", "5"))
                .satisfies(t -> assertStatus(t, 404));
    }

    @Test
    void addCertificatesUploadsAndPersists() {
        when(fileUploadClient.upload(anyString(), any(), any())).thenReturn(ok("c1"));
        when(certificateRepo.findByCpIdOrderByUploadedAtDesc("CP-1"))
                .thenReturn(List.of(new ComplianceCertificate("CP-1", "c1", "cert.pdf", "u", Instant.now())));

        List<AttachmentView> out = service().addCertificates("CP-1", List.of(pdf()));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).fileId()).isEqualTo("c1");
        verify(certificateRepo).save(any(ComplianceCertificate.class));
    }

    @Test
    void addCertificatesForAPeriodStoresThePeriodEnd() {
        java.time.LocalDate q1 = java.time.LocalDate.of(2026, 3, 31);
        when(fileUploadClient.upload(anyString(), any(), any())).thenReturn(ok("c1"));
        when(certificateRepo.findByCpIdOrderByUploadedAtDesc("CP-1"))
                .thenReturn(List.of(new ComplianceCertificate("CP-1", "c1", "cert.pdf", "u", Instant.now(), q1)));

        List<AttachmentView> out = service().addCertificates("CP-1", List.of(pdf()), q1);

        assertThat(out.get(0).periodEnd()).isEqualTo(q1);
        org.mockito.ArgumentCaptor<ComplianceCertificate> saved =
                org.mockito.ArgumentCaptor.forClass(ComplianceCertificate.class);
        verify(certificateRepo).save(saved.capture());
        assertThat(saved.getValue().getPeriodEnd()).isEqualTo(q1);
    }

    @Test
    void addCertificatesRejectsNoFiles() {
        assertThatThrownBy(() -> service().addCertificates("CP-1", List.of()))
                .satisfies(t -> assertStatus(t, 400));
    }

    @Test
    void removeCertificateDeletesWhenScoped() {
        ComplianceCertificate cert = new ComplianceCertificate("CP-1", "c1", "cert.pdf", "u", Instant.now());
        when(certificateRepo.findByIdAndCpId("7", "CP-1")).thenReturn(Optional.of(cert));
        when(certificateRepo.findByCpIdOrderByUploadedAtDesc("CP-1")).thenReturn(List.of());

        assertThat(service().removeCertificate("CP-1", "7")).isEmpty();
        verify(certificateRepo).delete(cert);
    }

    @Test
    void removeCertificateRejectsMissing() {
        when(certificateRepo.findByIdAndCpId("7", "CP-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().removeCertificate("CP-1", "7"))
                .satisfies(t -> assertStatus(t, 404));
    }

    @Test
    void addAttachmentsRejectsNullFileList() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);

        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", null))
                .satisfies(t -> assertStatus(t, 400));
        verify(fileUploadClient, never()).upload(anyString(), any(), any());
    }

    @Test
    void addAttachmentsRejectsAllEmptyFiles() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        MockMultipartFile empty = new MockMultipartFile("files", "e.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", List.of(empty)))
                .satisfies(t -> assertStatus(t, 400));
        verify(fileUploadClient, never()).upload(anyString(), any(), any());
    }

    @Test
    void addAttachmentsRejectsAnEmptyFileAmongValidOnes() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        MockMultipartFile empty = new MockMultipartFile("files", "e.pdf", "application/pdf", new byte[0]);

        // empty file listed first so per-file validate() rejects before any upload
        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", List.of(empty, pdf())))
                .satisfies(t -> assertStatus(t, 400));
        verify(fileUploadClient, never()).upload(anyString(), any(), any());
    }

    @Test
    void addAttachmentsRejectsBlankFilename() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        MockMultipartFile blank = new MockMultipartFile("files", "", "application/pdf", "d".getBytes());

        // blank name ⇒ sanitised to "upload" ⇒ no extension ⇒ unsupported
        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", List.of(blank)))
                .satisfies(t -> assertStatus(t, 400));
    }

    @Test
    void addAttachmentsRejectsNullFilename() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        MultipartFile noName = org.mockito.Mockito.mock(MultipartFile.class);
        when(noName.isEmpty()).thenReturn(false);
        when(noName.getSize()).thenReturn(10L);
        when(noName.getOriginalFilename()).thenReturn(null);

        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", List.of(noName)))
                .satisfies(t -> assertStatus(t, 400));
    }

    @Test
    void addAttachmentsRejectsPathOnlyFilename() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        MockMultipartFile pathOnly =
                new MockMultipartFile("files", "subdir/", "application/pdf", "d".getBytes());

        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", List.of(pathOnly)))
                .satisfies(t -> assertStatus(t, 400));
    }

    @Test
    void addAttachmentsSurfacesNullResultAsBadGateway() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        when(fileUploadClient.upload(anyString(), any(), any())).thenReturn(null);

        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", List.of(pdf())))
                .satisfies(t -> assertStatus(t, 502));
        verify(attachmentRepo, never()).save(any());
    }

    @Test
    void addAttachmentsSurfacesMissingFileIdAsBadGateway() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        when(fileUploadClient.upload(anyString(), any(), any()))
                .thenReturn(new FileUploadResult(true, null, "stored.pdf", 200, null));

        assertThatThrownBy(() -> service().addAttachments("CP-1", "10", List.of(pdf())))
                .satisfies(t -> assertStatus(t, 502));
        verify(attachmentRepo, never()).save(any());
    }

    @Test
    void addAttachmentsFallsBackToRequestNameWhenServiceOmitsName() {
        when(appliedRepo.existsByIdAndCpId("10", "CP-1")).thenReturn(true);
        when(fileUploadClient.upload(anyString(), any(), any()))
                .thenReturn(new FileUploadResult(true, "f1", null, 200, null));

        service().addAttachments("CP-1", "10", List.of(pdf()));

        ArgumentCaptor<CovenantAttachment> saved = ArgumentCaptor.forClass(CovenantAttachment.class);
        verify(attachmentRepo).save(saved.capture());
        assertThat(saved.getValue().getFileName()).isEqualTo("evidence.pdf");
    }

    @Test
    void addCertificatesFallsBackToRequestNameWhenServiceReturnsBlankName() {
        when(fileUploadClient.upload(anyString(), any(), any()))
                .thenReturn(new FileUploadResult(true, "c1", "  ", 200, null));

        service().addCertificates("CP-1", List.of(pdf()));

        ArgumentCaptor<ComplianceCertificate> saved = ArgumentCaptor.forClass(ComplianceCertificate.class);
        verify(certificateRepo).save(saved.capture());
        assertThat(saved.getValue().getFileName()).isEqualTo("evidence.pdf");
    }

    @Test
    void downloadAttachmentReturnsBytesWithStoredName() {
        CovenantAttachment att =
                new CovenantAttachment("CP-1", "10", "f1", "stored-evidence.pdf", "u", Instant.now());
        when(attachmentRepo.findByIdAndCpIdAndAppliedCovenantId("5", "CP-1", "10"))
                .thenReturn(Optional.of(att));
        when(fileUploadClient.download("f1"))
                .thenReturn(new FileDownloadResult("PDF-BYTES".getBytes(), "application/pdf", "svc-name.pdf"));

        FileDownloadResult out = service().downloadAttachment("CP-1", "10", "5");

        assertThat(out.content()).isEqualTo("PDF-BYTES".getBytes());
        assertThat(out.contentType()).isEqualTo("application/pdf");
        // The name we stored on upload wins over whatever the file service echoes back.
        assertThat(out.fileName()).isEqualTo("stored-evidence.pdf");
    }

    @Test
    void downloadAttachmentRejectsMissing() {
        when(attachmentRepo.findByIdAndCpIdAndAppliedCovenantId("5", "CP-1", "10"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().downloadAttachment("CP-1", "10", "5"))
                .satisfies(t -> assertStatus(t, 404));
        verify(fileUploadClient, never()).download(anyString());
    }

    @Test
    void downloadCertificateReturnsBytesWithStoredName() {
        ComplianceCertificate cert =
                new ComplianceCertificate("CP-1", "c1", "stored-cert.pdf", "u", Instant.now());
        when(certificateRepo.findByIdAndCpId("7", "CP-1")).thenReturn(Optional.of(cert));
        when(fileUploadClient.download("c1"))
                .thenReturn(new FileDownloadResult("C".getBytes(), "application/pdf", "svc.pdf"));

        FileDownloadResult out = service().downloadCertificate("CP-1", "7");

        assertThat(out.fileName()).isEqualTo("stored-cert.pdf");
        assertThat(out.content()).isEqualTo("C".getBytes());
    }

    @Test
    void downloadCertificateFallsBackToServiceNameWhenStoredNameBlank() {
        ComplianceCertificate cert =
                new ComplianceCertificate("CP-1", "c1", "  ", "u", Instant.now());
        when(certificateRepo.findByIdAndCpId("7", "CP-1")).thenReturn(Optional.of(cert));
        when(fileUploadClient.download("c1"))
                .thenReturn(new FileDownloadResult("C".getBytes(), "application/pdf", "svc-fallback.pdf"));

        FileDownloadResult out = service().downloadCertificate("CP-1", "7");

        // Stored name blank ⇒ fall back to the file service's name.
        assertThat(out.fileName()).isEqualTo("svc-fallback.pdf");
    }

    @Test
    void downloadCertificateRejectsMissing() {
        when(certificateRepo.findByIdAndCpId("7", "CP-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().downloadCertificate("CP-1", "7"))
                .satisfies(t -> assertStatus(t, 404));
        verify(fileUploadClient, never()).download(anyString());
    }

    @Test
    void listsMapEntitiesToViews() {
        when(attachmentRepo.findByAppliedCovenantIdOrderByUploadedAtDesc("10"))
                .thenReturn(List.of(new CovenantAttachment("CP-1", "10", "f1", "e.pdf", "u", Instant.now())));
        when(certificateRepo.findByCpIdOrderByUploadedAtDesc("CP-1"))
                .thenReturn(List.of(new ComplianceCertificate("CP-1", "c1", "cert.pdf", "u", Instant.now())));

        AttachmentService svc = service();
        assertThat(svc.attachmentsForCovenant("10")).singleElement()
                .satisfies(v -> assertThat(v.fileId()).isEqualTo("f1"));
        assertThat(svc.certificatesForCp("CP-1")).singleElement()
                .satisfies(v -> assertThat(v.fileId()).isEqualTo("c1"));
    }
}
