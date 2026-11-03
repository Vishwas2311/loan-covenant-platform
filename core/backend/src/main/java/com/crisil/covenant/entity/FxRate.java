package com.crisil.covenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.UuidGenerator;

/** FR-23: one unit of {@code fromCurrency} is worth {@code rate} units of {@code toCurrency} on {@code rateDate}. */
@Entity
@Table(name = "cov_fx_rate")
public class FxRate extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "from_currency", nullable = false, length = 3)
    private String fromCurrency;

    @Column(name = "to_currency", nullable = false, length = 3)
    private String toCurrency;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(name = "rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal rate;

    @Column(name = "source", length = 64)
    private String source;

    protected FxRate() {
        // for JPA
    }

    public FxRate(String fromCurrency, String toCurrency, LocalDate rateDate, BigDecimal rate, String source) {
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
        this.rateDate = rateDate;
        this.rate = rate;
        this.source = source;
    }

    public String getId() {
        return id;
    }

    public String getFromCurrency() {
        return fromCurrency;
    }

    public String getToCurrency() {
        return toCurrency;
    }

    public LocalDate getRateDate() {
        return rateDate;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
