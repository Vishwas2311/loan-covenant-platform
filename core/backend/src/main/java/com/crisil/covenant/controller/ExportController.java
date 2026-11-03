package com.crisil.covenant.controller;

import com.crisil.covenant.dto.PeriodComparison;
import com.crisil.covenant.security.CpAccessGuard;
import com.crisil.covenant.service.ComparisonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FR-24: period comparison for a CP, and Excel exports of it and of the covenant library. */
@RestController
@Tag(name = "Exports", description = "Period comparison and Excel exports")
public class ExportController {

    static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ComparisonService service;
    private final CpAccessGuard guard;

    public ExportController(ComparisonService service, CpAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/api/v1/monitoring/cp/{cpId}/comparison")
    @Operation(summary = "FR-24: the CP's covenants across its last assessment dates (newest first)")
    public PeriodComparison comparison(@PathVariable String cpId,
                                       @RequestParam(defaultValue = "4") int periods) {
        guard.requireCp(cpId);
        return service.compare(cpId, periods);
    }

    @GetMapping("/api/v1/monitoring/cp/{cpId}/comparison.xlsx")
    @Operation(summary = "FR-24: the period comparison as an Excel file")
    public ResponseEntity<byte[]> comparisonXlsx(@PathVariable String cpId,
                                                 @RequestParam(defaultValue = "8") int periods) {
        guard.requireCp(cpId);
        return xlsx(service.compareXlsx(cpId, periods), "covenants-" + cpId + ".xlsx");
    }

    @GetMapping("/api/v1/covenants/export.xlsx")
    @Operation(summary = "FR-24: the covenant library as an Excel file")
    public ResponseEntity<byte[]> libraryXlsx() {
        return xlsx(service.libraryXlsx(), "covenant-library.xlsx");
    }

    private static ResponseEntity<byte[]> xlsx(byte[] body, String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX);
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
