package com.crisil.covenant.service;

import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.dto.AttachmentView;
import com.crisil.covenant.dto.CertificateRequirementView;
import com.crisil.covenant.entity.AccessKey;
import com.crisil.covenant.entity.AppliedCovenant;
import com.crisil.covenant.entity.PortalAccess;
import com.crisil.covenant.entity.PortalSubmission;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.repository.PortalAccessRepository;
import com.crisil.covenant.repository.PortalSubmissionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * C1: the borrower portal. A borrower user sees only the CPs the bank gave them access to, and for
 * each only what is due (compliance certificates and the covenants the bank checks, never results or
 * internal notes). They can send a signed certificate, which is stored like one the RM uploads, and a
 * financial statement, which Core forwards to DPS with its own service identity. Every submission is
 * recorded with who sent it.
 */
@Service
public class PortalService {

    private static final Logger LOG = LoggerFactory.getLogger(PortalService.class);
    static final long MAX_BYTES = 20L * 1024 * 1024;

    public record PortalCp(String cpId, String name) {}

    public record Requirement(String id, LocalDate periodEnd, LocalDate dueDate, String state, long overdueDays,
                              List<String> files) {}

    public record CovenantDue(String name, LocalDate nextDueDate) {}

    public record Submission(String kind, String fileName, LocalDate periodEnd, String submittedBy,
                             Instant submittedAt, String note) {}

    public record PortalView(String cpId, String name, List<Requirement> requirements, List<CovenantDue> covenants,
                             List<Submission> submissions) {}

    public record AccessView(String cpId, String userId, String grantedBy, Instant grantedAt) {}

    private final PortalAccessRepository access;
    private final PortalSubmissionRepository submissions;
    private final CertificateRequirementService requirements;
    private final AttachmentService attachments;
    private final AppliedCovenantRepository appliedRepo;
    private final CreditProposalClient creditProposals;
    private final WebClient dps;
    private final Clock clock;

    public PortalService(PortalAccessRepository access, PortalSubmissionRepository submissions,
                         CertificateRequirementService requirements, AttachmentService attachments,
                         AppliedCovenantRepository appliedRepo, CreditProposalClient creditProposals,
                         @Qualifier("dpsWebClient") WebClient dps, Clock clock) {
        this.access = access;
        this.submissions = submissions;
        this.requirements = requirements;
        this.attachments = attachments;
        this.appliedRepo = appliedRepo;
        this.creditProposals = creditProposals;
        this.dps = dps;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- bank side

    @Transactional(readOnly = true)
    public List<AccessView> accessFor(String cpId) {
        return access.findByCp(cpId).stream().map(PortalService::view).toList();
    }

    @Transactional
    public List<AccessView> grant(String cpId, String userId, String by) {
        String user = userId == null ? "" : userId.trim();
        if (!user.matches("[A-Za-z0-9._@-]{3,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a valid user id");
        }
        if (!access.existsById(new AccessKey(cpId, user))) {
            access.save(new PortalAccess(cpId, user, by, Instant.now(clock)));
        }
        return accessFor(cpId);
    }

    @Transactional
    public List<AccessView> revoke(String cpId, String userId) {
        access.deleteById(new AccessKey(cpId, userId));
        return accessFor(cpId);
    }

    private static AccessView view(PortalAccess a) {
        return new AccessView(a.getCpId(), a.getUserId(), a.getGrantedBy(), a.getGrantedAt());
    }

    // ---------------------------------------------------------------- borrower side

    @Transactional(readOnly = true)
    public List<PortalCp> cpsFor(String user) {
        return access.findByUser(user).stream().map(a -> new PortalCp(a.getCpId(), name(a.getCpId()))).toList();
    }

    /** 404, not 403, for a CP the borrower was not given, so they cannot learn which CPs exist. */
    void requireAccess(String user, String cpId) {
        if (user == null || !access.existsById(new AccessKey(cpId, user))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }

    @Transactional(readOnly = true)
    public PortalView view(String user, String cpId) {
        requireAccess(user, cpId);
        List<Requirement> reqs = requirements.forCp(cpId).stream().map(PortalService::requirement).toList();
        List<CovenantDue> covenants = appliedRepo.findByCpIdOrderByIdAsc(cpId).stream()
                .filter(a -> a.getStatus() == AppliedCovenantStatus.ACTIVE)
                .map(PortalService::covenant).toList();
        List<Submission> sent = submissions.findByCpIdOrderBySubmittedAtDesc(cpId).stream()
                .map(s -> new Submission(s.getKind(), s.getFileName(), s.getPeriodEnd(), s.getSubmittedBy(),
                        s.getSubmittedAt(), s.getNote()))
                .toList();
        return new PortalView(cpId, name(cpId), reqs, covenants, sent);
    }

    private static Requirement requirement(CertificateRequirementView q) {
        return new Requirement(q.id(), q.periodEnd(), q.dueDate(), q.state().name(), q.overdueDays(),
                q.certificates().stream().map(AttachmentView::fileName).toList());
    }

    private static CovenantDue covenant(AppliedCovenant a) {
        return new CovenantDue(a.getCovenantMaster().getName(), null);
    }

    private String name(String cpId) {
        try {
            CreditProposalDetails d = creditProposals.lookup(cpId);
            return d == null ? null : d.name();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void checkFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a file to send");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The file is larger than 20 MB");
        }
    }

    @Transactional
    public PortalView submitCertificate(String user, String cpId, MultipartFile file, LocalDate periodEnd,
                                        String signedBy, boolean confirm) {
        requireAccess(user, cpId);
        checkFile(file);
        if (!confirm || signedBy == null || signedBy.trim().length() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Confirm the certificate and give the name of the person signing it");
        }
        boolean expected = requirements.forCp(cpId).stream()
                .anyMatch(q -> q.periodEnd().equals(periodEnd) && q.state() != CertificateRequirementView.State.RECEIVED);
        if (!expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No certificate is waiting for that period");
        }
        List<AttachmentView> stored = attachments.addCertificates(cpId, List.of(file), periodEnd);
        String ref = stored.isEmpty() ? null : stored.get(0).id();
        submissions.save(new PortalSubmission(cpId, "CERTIFICATE", name(file), periodEnd, user, Instant.now(clock),
                "Signed by " + signedBy.trim(), ref));
        return view(user, cpId);
    }

    @Transactional
    public PortalView submitStatement(String user, String cpId, MultipartFile file, LocalDate periodEnd) {
        requireAccess(user, cpId);
        checkFile(file);
        if (periodEnd == null || periodEnd.isAfter(LocalDate.now(clock))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the period end the statement covers");
        }
        String documentId = forwardToDps(cpId, file, periodEnd);
        submissions.save(new PortalSubmission(cpId, "STATEMENT", name(file), periodEnd, user, Instant.now(clock), null,
                documentId));
        return view(user, cpId);
    }

    private static String name(MultipartFile file) {
        String original = file.getOriginalFilename();
        String n = original == null ? "file" : original;
        n = n.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        return n.length() > 255 ? n.substring(0, 255) : n;
    }

    /** Uploads the statement to DPS as Core (service token); DPS reads it like any other statement. */
    String forwardToDps(String cpId, MultipartFile file, LocalDate periodEnd) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file could not be read");
        }
        String fileName = name(file);
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(bytes)).filename(fileName).contentType(MediaType.APPLICATION_OCTET_STREAM);
        body.part("cpIds", cpId);
        body.part("type", "FINANCIAL_STATEMENT");
        body.part("asOfDate", periodEnd.toString());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = dps.post().uri("/dps/v1/documents")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve().bodyToMono(Map.class).block(java.time.Duration.ofSeconds(30));
            return result == null ? null : String.valueOf(result.get("documentId"));
        } catch (RuntimeException ex) {
            LOG.warn("Portal statement upload to DPS failed for {}: {}", cpId.replace('\n', '_').replace('\r', '_'),
                    ex.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The statement could not be passed on to the bank's document service; please try again later");
        }
    }
}
