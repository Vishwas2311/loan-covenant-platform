package com.crisil.covenant.service;

import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.config.AuditingConfig;
import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.service.FileUploadClient;
import com.crisil.covenant.dto.FileDownloadResult;
import com.crisil.covenant.dto.FileUploadResult;
import com.crisil.covenant.dto.AttachmentView;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.crisil.covenant.entity.ComplianceCertificate;
import com.crisil.covenant.entity.CovenantAttachment;
import com.crisil.covenant.repository.ComplianceCertificateRepository;
import com.crisil.covenant.repository.CovenantAttachmentRepository;

/**
 * Uploads evidence attachments (per covenant) and CP-level compliance certificates (US-MON-17) by
 * forwarding each file through the external file-upload service and persisting the returned
 * {@code fileId}. Uploads are non-mandatory; a failed external call surfaces a clear error and
 * never corrupts existing data (AC5). Basic type/size validation runs before any external call (AC6).
 */
@Service
@Transactional(readOnly = true)
public class AttachmentService {

    /** Maximum accepted file size (US-MON-17 AC6). */
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /** Accepted file extensions (US-MON-17 AC6). */
    static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "doc", "docx", "xls", "xlsx", "csv", "txt", "png", "jpg", "jpeg");

    private final FileUploadClient fileUploadClient;
    private final CovenantAttachmentRepository attachmentRepo;
    private final ComplianceCertificateRepository certificateRepo;
    private final AppliedCovenantRepository appliedRepo;

    public AttachmentService(FileUploadClient fileUploadClient,
                             CovenantAttachmentRepository attachmentRepo,
                             ComplianceCertificateRepository certificateRepo,
                             AppliedCovenantRepository appliedRepo) {
        this.fileUploadClient = fileUploadClient;
        this.attachmentRepo = attachmentRepo;
        this.certificateRepo = certificateRepo;
        this.appliedRepo = appliedRepo;
    }

    /** Evidence attachments on one covenant row (US-MON-17 AC3), newest first. */
    public List<AttachmentView> attachmentsForCovenant(String appliedCovenantId) {
        return attachmentRepo.findByAppliedCovenantIdOrderByUploadedAtDesc(appliedCovenantId)
                .stream().map(AttachmentView::from).toList();
    }

    /** CP-level compliance certificates (US-MON-17 AC4), newest first. */
    public List<AttachmentView> certificatesForCp(String cpId) {
        return certificateRepo.findByCpIdOrderByUploadedAtDesc(cpId)
                .stream().map(AttachmentView::from).toList();
    }

    /** Upload one/more evidence files onto a covenant row (US-MON-17 AC1). Returns the refreshed list. */
    @Transactional
    public List<AttachmentView> addAttachments(String cpId, String appliedId, List<MultipartFile> files) {
        if (!appliedRepo.existsByIdAndCpId(appliedId, cpId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No covenant " + appliedId + " on CP " + cpId);
        }
        requireFiles(files);
        String uploadedBy = currentUser();
        for (MultipartFile file : files) {
            String fileName = validate(file);
            FileUploadResult result = forward(file, fileName);
            attachmentRepo.save(new CovenantAttachment(cpId, appliedId, result.fileId(),
                    displayName(result, fileName), uploadedBy, Instant.now()));
        }
        return attachmentsForCovenant(appliedId);
    }

    /** Remove one evidence attachment scoped to its CP+covenant (US-MON-17 AC3). */
    @Transactional
    public List<AttachmentView> removeAttachment(String cpId, String appliedId, String attachmentId) {
        CovenantAttachment existing = attachmentRepo
                .findByIdAndCpIdAndAppliedCovenantId(attachmentId, cpId, appliedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No attachment " + attachmentId + " on covenant " + appliedId));
        attachmentRepo.delete(existing);
        return attachmentsForCovenant(appliedId);
    }

    /**
     * Fetch one evidence attachment's bytes back from the file service (US-MON-23 AC6), guarded to the
     * owning CP + covenant via the same finder {@link #removeAttachment} uses (so an id from another
     * CP yields {@code 404}). The stored filename always wins over whatever the file service echoes,
     * so the browser saves it under the name the RM uploaded.
     */
    public FileDownloadResult downloadAttachment(String cpId, String appliedId, String attachmentId) {
        CovenantAttachment existing = attachmentRepo
                .findByIdAndCpIdAndAppliedCovenantId(attachmentId, cpId, appliedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No attachment " + attachmentId + " on covenant " + appliedId));
        return withStoredName(fileUploadClient.download(existing.getFileId()), existing.getFileName());
    }

    /** Upload one/more CP-level compliance-certificate files (US-MON-17 AC4). Returns refreshed list. */
    @Transactional
    public List<AttachmentView> addCertificates(String cpId, List<MultipartFile> files) {
        return addCertificates(cpId, files, null);
    }

    /** FR-22: upload certificates for one reporting period ({@code periodEnd} null = general). */
    @Transactional
    public List<AttachmentView> addCertificates(String cpId, List<MultipartFile> files, LocalDate periodEnd) {
        requireFiles(files);
        String uploadedBy = currentUser();
        for (MultipartFile file : files) {
            String fileName = validate(file);
            FileUploadResult result = forward(file, fileName);
            certificateRepo.save(new ComplianceCertificate(cpId, result.fileId(),
                    displayName(result, fileName), uploadedBy, Instant.now(), periodEnd));
        }
        return certificatesForCp(cpId);
    }

    /** Remove one CP-level compliance certificate (US-MON-17 AC4). */
    @Transactional
    public List<AttachmentView> removeCertificate(String cpId, String certificateId) {
        ComplianceCertificate existing = certificateRepo.findByIdAndCpId(certificateId, cpId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No compliance certificate " + certificateId + " on CP " + cpId));
        certificateRepo.delete(existing);
        return certificatesForCp(cpId);
    }

    /**
     * Fetch one CP-level compliance certificate's bytes back from the file service (US-MON-23 AC6),
     * guarded to its CP via the same finder {@link #removeCertificate} uses. The stored filename wins.
     */
    public FileDownloadResult downloadCertificate(String cpId, String certificateId) {
        ComplianceCertificate existing = certificateRepo.findByIdAndCpId(certificateId, cpId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No compliance certificate " + certificateId + " on CP " + cpId));
        return withStoredName(fileUploadClient.download(existing.getFileId()), existing.getFileName());
    }

    /** Prefer the filename we stored on upload over whatever the file service echoes back. */
    private static FileDownloadResult withStoredName(FileDownloadResult downloaded, String storedName) {
        String name = storedName == null || storedName.isBlank() ? downloaded.fileName() : storedName;
        return new FileDownloadResult(downloaded.content(), downloaded.contentType(), name);
    }

    private FileUploadResult forward(MultipartFile file, String fileName) {
        FileUploadResult result = fileUploadClient.upload(fileName, file.getContentType(), readBytes(file));
        if (result == null || !result.success() || result.fileId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The file-upload service could not store " + fileName);
        }
        return result;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file");
        }
    }

    private static void requireFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty() || files.stream().allMatch(MultipartFile::isEmpty)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At least one non-empty file is required");
        }
    }

    /** Basic type/size validation (US-MON-17 AC6); returns the sanitised base filename. */
    private static String validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An empty file cannot be uploaded");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File exceeds the 10 MB limit");
        }
        String fileName = sanitiseName(file.getOriginalFilename());
        String extension = extensionOf(fileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported file type; allowed: " + ALLOWED_EXTENSIONS);
        }
        return fileName;
    }

    private static String sanitiseName(String original) {
        if (original == null || original.isBlank()) {
            return "upload";
        }
        String slashed = original.replace('\\', '/');
        int slash = slashed.lastIndexOf('/');
        String base = slash < 0 ? slashed : slashed.substring(slash + 1);
        return base.isBlank() ? "upload" : base;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String displayName(FileUploadResult result, String fallback) {
        return result.fileName() == null || result.fileName().isBlank() ? fallback : result.fileName();
    }

    private static String currentUser() {
        return CurrentUserContext.currentUser().orElse(AuditingConfig.SYSTEM_USER);
    }
}
