package com.crisil.covenant.service;
import com.crisil.covenant.domain.DataCatalogResponse;

/**
 * Lists the available CP-keyed columns exposed by the REST Data API, so covenant authors can
 * discover which data points exist before referencing them in a formula.
 */
public interface DataCatalogClient {

    DataCatalogResponse fetchCatalog();
}
