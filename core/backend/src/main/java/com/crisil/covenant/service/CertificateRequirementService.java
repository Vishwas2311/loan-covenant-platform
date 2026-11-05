package com.crisil.covenant.service;

import com.crisil.covenant.dto.AttachmentView;
import com.crisil.covenant.dto.CertificateRequirementRequest;
import com.crisil.covenant.dto.CertificateRequirementView;
import com.crisil.covenant.dto.CertificateRequirementView.State;
import com.crisil.covenant.entity.CertificateRequirement;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.repository.CertificateRequirementRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-22: which periods a CP must certify, by when, and whether it has. The state is computed from the
 * CP's certificates (matched on {@code periodEnd}) and today's date on every read.
 */
@Service
public class CertificateRequirementService {

    private final CertificateRequirementRepository repo;
    private final AttachmentService attachmentService;
    private final Clock clock;
    private final OpsAuditService audit;

    public CertificateRequirementService(CertificateRequirementRepository repo, AttachmentService attachmentService,
                                         Clock clock, OpsAuditService audit) {
        this.repo = repo;
        this.attachmentService = attachmentService;
        this.clock = clock;
        this.audit = audit;
    }

    public List<CertificateRequirementView> forCp(String cpId) {
        List<AttachmentView> certificates = attachmentService.certificatesForCp(cpId);
        LocalDate today = LocalDate.now(clock);
        return repo.findByCpIdOrderByPeriodEndAsc(cpId).stream()
                .map(r -> view(r, certificates, today))
                .toList();
    }

    /** Creates the requirement, or updates the due date and note when the period already has one. */
    @Transactional
    public CertificateRequirementView upsert(String cpId, CertificateRequirementRequest req) {
        if (req.dueDate().isBefore(req.periodEnd())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DUE_BEFORE_PERIOD_END",
                    "The due date cannot be before the period end");
        }
        CertificateRequirement r = repo.findByCpIdAndPeriodEnd(cpId, req.periodEnd())
                .orElseGet(() -> new CertificateRequirement(cpId, req.periodEnd(), req.dueDate(), req.note()));
        r.setDueDate(req.dueDate());
        r.setNote(req.note());
        CertificateRequirement saved = repo.save(r);
        return view(saved, attachmentService.certificatesForCp(cpId), LocalDate.now(clock));
    }

    @Transactional
    public void remove(String cpId, String id) {
        CertificateRequirement r = repo.findByIdAndCpId(id, cpId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUIREMENT_NOT_FOUND",
                        "No certificate requirement " + id + " on CP " + cpId));
        repo.delete(r);
        repo.flush();
        audit.recordSuccess("DELETE_CERTIFICATE_DUE_DATE", "CP", cpId, "periodEnd=" + r.getPeriodEnd(),
                "Removed certificate due date " + r.getDueDate() + " (" + id + ")");
    }

    static CertificateRequirementView view(CertificateRequirement r, List<AttachmentView> certificates,
                                           LocalDate today) {
        List<AttachmentView> matching = certificates.stream()
                .filter(c -> r.getPeriodEnd().equals(c.periodEnd()))
                .toList();
        State state;
        long overdue = 0;
        if (!matching.isEmpty()) {
            state = State.RECEIVED;
        } else if (today.isAfter(r.getDueDate())) {
            state = State.OVERDUE;
            overdue = ChronoUnit.DAYS.between(r.getDueDate(), today);
        } else {
            state = State.PENDING;
        }
        return new CertificateRequirementView(r.getId(), r.getCpId(), r.getPeriodEnd(), r.getDueDate(), r.getNote(),
                state, overdue, matching);
    }
}
