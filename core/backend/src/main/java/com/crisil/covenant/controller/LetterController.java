package com.crisil.covenant.controller;

import com.crisil.covenant.config.CurrentUserContext;
import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.service.LetterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Locale;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/monitoring")
@Tag(name = "Borrower letters", description = "C12: breach notices and waiver or extension letters")
public class LetterController {

    static final MediaType DOCX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final LetterService service;
    private final CpAccessGuard guard;

    public LetterController(LetterService service, CpAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/cp/{cpId}/covenants/{appliedId}/letter")
    @Operation(summary = "A borrower letter for this covenant as a Word file",
            description = "kind = BREACH_NOTICE (covenant breached), WAIVER_GRANTED or EXTENSION_GRANTED (approved).")
    public ResponseEntity<byte[]> letter(@PathVariable String cpId, @PathVariable String appliedId,
                                         @RequestParam LetterService.Kind kind) {
        guard.requireCp(cpId);
        byte[] body = service.letter(cpId, appliedId, kind, CurrentUserContext.currentUser().orElse(null));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(DOCX);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(kind.name().toLowerCase(Locale.ROOT) + "-" + cpId.replaceAll("[^A-Za-z0-9-]", "_") + ".docx")
                .build());
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
