package com.crisil.covenant.service;

import com.crisil.covenant.domain.DataSeriesResponse;
import com.crisil.covenant.domain.RestEndpoint;
import java.time.LocalDate;
import java.util.List;

public interface DataApiClient {

    /**
     * WS3/US-DS-12: the single as-of-aware read for one entity, routed to the source's own
     * {@code endpoint} (its {@code baseUrl}/{@code valuesPath}) rather than a global base URL. When
     * {@code from} is {@code null} the source returns a single latest observation {@code <= to} (a
     * point-in-time snapshot); when {@code from} is non-null it returns every dated observation in the
     * inclusive window {@code [from, to]}. Both shapes come back as a {@link DataSeriesResponse}, so one
     * contract serves point-in-time and windowed reads.
     */
    DataSeriesResponse fetchValues(RestEndpoint endpoint, String cpId, LocalDate from, LocalDate to,
                                   List<String> keys);
}
