package com.crisil.covenant.controller;

import com.crisil.covenant.dto.ConversionResult;
import com.crisil.covenant.dto.FxRateRequest;
import com.crisil.covenant.dto.FxRateView;
import com.crisil.covenant.service.FxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FR-23: FX rates (ADMIN writes, see SecurityConfig) and conversion to the reporting currency. */
@RestController
@RequestMapping("/api/v1/fx")
@Tag(name = "Currency", description = "Reporting currency and FX rates")
public class FxController {

    private final FxService service;

    public FxController(FxService service) {
        this.service = service;
    }

    @GetMapping("/rates")
    @Operation(summary = "FR-23: all FX rates, newest first")
    public List<FxRateView> rates() {
        return service.rates();
    }

    @PutMapping("/rates")
    @Operation(summary = "FR-23: record or correct the rate for a currency pair and date")
    public FxRateView upsert(@Valid @RequestBody FxRateRequest request) {
        return service.upsert(request);
    }

    @DeleteMapping("/rates/{id}")
    @Operation(summary = "FR-23: remove an FX rate")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/convert")
    @Operation(summary = "FR-23: convert an amount (to the reporting currency when 'to' is omitted)")
    public ConversionResult convert(@RequestParam BigDecimal amount, @RequestParam String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return to == null || to.isBlank() ? service.toReporting(amount, from, date)
                : service.convert(amount, from, to, date);
    }
}
