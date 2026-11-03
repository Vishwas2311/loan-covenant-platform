package com.crisil.covenant.service;

import com.crisil.covenant.dto.CpReviewRow;
import com.crisil.covenant.entity.BorrowerContact;
import com.crisil.covenant.entity.LetterLog;
import com.crisil.covenant.enums.AssessmentResult;
import com.crisil.covenant.enums.CovenantDispositionStatus;
import com.crisil.covenant.repository.BorrowerContactRepository;
import com.crisil.covenant.repository.LetterLogRepository;
import com.crisil.covenant.util.DocxWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * C12: borrower letters, filled in from the covenant's current state: a breach notice for a breached
 * covenant, and a confirmation once a waiver or extension is approved. Each letter is logged. The text
 * is a starting point the RM can edit in Word before it is sent.
 */
@Service
public class LetterService {

    public enum Kind { BREACH_NOTICE, WAIVER_GRANTED, EXTENSION_GRANTED }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final ReviewService reviewService;
    private final BorrowerContactRepository contacts;
    private final LetterLogRepository log;
    private final Clock clock;

    public LetterService(ReviewService reviewService, BorrowerContactRepository contacts, LetterLogRepository log,
                         Clock clock) {
        this.reviewService = reviewService;
        this.contacts = contacts;
        this.log = log;
        this.clock = clock;
    }

    @Transactional
    public byte[] letter(String cpId, String appliedId, Kind kind, String user) {
        CpReviewRow r = reviewService.reviewRows(cpId).stream()
                .filter(x -> x.appliedCovenantId().equals(appliedId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Covenant " + appliedId + " not found on CP " + cpId));
        boolean fits = switch (kind) {
            case BREACH_NOTICE -> r.latestResult() == AssessmentResult.BREACHED;
            case WAIVER_GRANTED -> r.dispositionStatus() == CovenantDispositionStatus.WAIVER;
            case EXTENSION_GRANTED -> r.dispositionStatus() == CovenantDispositionStatus.EXTENSION;
        };
        if (!fits) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, switch (kind) {
                case BREACH_NOTICE -> "This covenant is not breached";
                case WAIVER_GRANTED -> "No approved waiver on this covenant";
                case EXTENSION_GRANTED -> "No approved extension on this covenant";
            });
        }
        LocalDate today = LocalDate.now(clock);
        String to = contacts.findById(cpId).map(BorrowerContact::getContactName).orElse("The Borrower");
        DocxWriter d = new DocxWriter();
        d.paragraph(DATE.format(today)).blank().paragraph("To: " + to).blank();
        d.bold("Facility: ", cpId).bold("Covenant: ", r.covenantName()).bold("Requirement: ", text(r.conditionExpression()));
        if (r.computedValue() != null) {
            d.bold("Latest figure: ", r.computedValue());
        }
        d.blank();
        switch (kind) {
            case BREACH_NOTICE -> {
                d.heading("Notice of covenant breach");
                d.paragraph("Dear " + to + ",");
                d.paragraph("Our review of the latest information shows that the covenant above was not met"
                        + (r.lastAssessedAt() == null ? "" : " when it was checked on "
                        + DATE.format(r.lastAssessedAt().atZone(clock.getZone()).toLocalDate())) + ".");
                d.paragraph("Under the facility agreement this is a breach. Please contact us within 14 days with "
                        + "an explanation and the steps you plan to take. The bank reserves all its rights under "
                        + "the agreement; this letter does not waive any of them.");
            }
            case WAIVER_GRANTED -> {
                d.heading("Waiver of covenant breach");
                d.paragraph("Dear " + to + ",");
                d.paragraph("Further to your request, the bank has agreed to waive the breach of the covenant "
                        + "above for this test period only." + reason(r));
                d.paragraph("All other terms of the facility agreement remain in full force, and the covenant will "
                        + "be tested again as usual at the next test date.");
            }
            case EXTENSION_GRANTED -> {
                d.heading("Extension of time to comply");
                d.paragraph("Dear " + to + ",");
                d.paragraph("The bank has agreed to give you until "
                        + (r.extensionDate() == null ? "the agreed date" : DATE.format(r.extensionDate()))
                        + " to meet the covenant above." + reason(r));
                d.paragraph("If the covenant is still not met by then, the breach will stand. All other terms of "
                        + "the facility agreement remain in full force.");
            }
        }
        d.blank().paragraph("Yours sincerely,").blank().paragraph(user == null ? "" : user).paragraph("Relationship Manager");
        log.save(new LetterLog(cpId, appliedId, kind.name(), user == null ? "system" : user, Instant.now(clock)));
        return d.toBytes();
    }

    private static String reason(CpReviewRow r) {
        return r.note() == null || r.note().isBlank() ? "" : " Reason recorded: " + r.note().trim() + ".";
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }
}
