package com.crisil.covenant.repository;

import com.crisil.covenant.entity.FxRate;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FxRateRepository extends JpaRepository<FxRate, String> {

    Optional<FxRate> findFirstByFromCurrencyAndToCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
            String fromCurrency, String toCurrency, LocalDate onOrBefore);

    Optional<FxRate> findByFromCurrencyAndToCurrencyAndRateDate(String fromCurrency, String toCurrency,
                                                                 LocalDate rateDate);

    List<FxRate> findAllByOrderByRateDateDescFromCurrencyAsc();
}
