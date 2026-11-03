package com.crisil.covenant.service;
import com.crisil.covenant.domain.CovenantAttributes;

public interface CovenantAttributesClient {

    CovenantAttributes fetch(String cpId);
}
