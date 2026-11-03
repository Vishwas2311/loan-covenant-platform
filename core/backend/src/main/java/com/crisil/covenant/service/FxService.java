package com.crisil.covenant.service;

import com.crisil.covenant.dto.ConversionResult;
import com.crisil.covenant.dto.FxRateRequest;
import com.crisil.covenant.dto.FxRateView;
import com.crisil.covenant.entity.FxRate;
import com.crisil.covenant.exception.ApiException;
import com.crisil.covenant.repository.FxRateRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-23: converts amounts between currencies with the latest rate on or before the amount's date. A
 * direct rate wins; otherwise the inverse of the opposite direction is used. No rate means 422, never
 * a guessed number.
 */
@Service
public class FxService {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    private final FxRateRepository repo;
    private final ReportingSettingsService settings;

    public FxService(FxRateRepository repo, ReportingSettingsService settings) {
        this.repo = repo;
        this.settings = settings;
    }

    public List<FxRateView> rates() {
        return repo.findAllByOrderByRateDateDescFromCurrencyAsc().stream().map(FxRateView::from).toList();
    }

    @Transactional
    public FxRateView upsert(FxRateRequest req) {
        if (req.fromCurrency().equals(req.toCurrency())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_CURRENCY", "From and to currency must differ");
        }
        FxRate rate = repo.findByFromCurrencyAndToCurrencyAndRateDate(req.fromCurrency(), req.toCurrency(),
                        req.rateDate())
                .orElseGet(() -> new FxRate(req.fromCurrency(), req.toCurrency(), req.rateDate(), req.rate(),
                        req.source()));
        rate.setRate(req.rate());
        rate.setSource(req.source());
        return FxRateView.from(repo.save(rate));
    }

    @Transactional
    public void delete(String id) {
        FxRate rate = repo.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "FX_RATE_NOT_FOUND", "No FX rate " + id));
        repo.delete(rate);
    }

    /** Converts to the organisation's reporting currency. */
    public ConversionResult toReporting(BigDecimal amount, String fromCurrency, LocalDate date) {
        return convert(amount, fromCurrency, settings.reportingCurrency(), date);
    }

    public ConversionResult convert(BigDecimal amount, String from, String to, LocalDate date) {
        if (from.equals(to)) {
            return new ConversionResult(amount, from, amount, to, BigDecimal.ONE, null, false);
        }
        Optional<FxRate> direct =
                repo.findFirstByFromCurrencyAndToCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(from, to, date);
        if (direct.isPresent()) {
            BigDecimal rate = direct.get().getRate();
            return new ConversionResult(amount, from, amount.multiply(rate, MC), to, rate,
                    direct.get().getRateDate(), false);
        }
        FxRate inverse = repo.findFirstByFromCurrencyAndToCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                        to, from, date)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FX_RATE_MISSING",
                        "No " + from + "/" + to + " rate on or before " + date));
        BigDecimal rate = BigDecimal.ONE.divide(inverse.getRate(), MC);
        return new ConversionResult(amount, from, amount.divide(inverse.getRate(), MC), to, rate,
                inverse.getRateDate(), true);
    }
}
