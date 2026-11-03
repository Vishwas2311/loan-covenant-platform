package com.crisil.covenant.dto;

import com.crisil.covenant.entity.FxRate;
import java.math.BigDecimal;
import java.time.LocalDate;

public record FxRateView(String id, String fromCurrency, String toCurrency, LocalDate rateDate, BigDecimal rate,
                         String source) {

    public static FxRateView from(FxRate r) {
        return new FxRateView(r.getId(), r.getFromCurrency(), r.getToCurrency(), r.getRateDate(), r.getRate(),
                r.getSource());
    }
}
