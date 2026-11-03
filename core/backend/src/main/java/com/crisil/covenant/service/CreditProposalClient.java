package com.crisil.covenant.service;
import com.crisil.covenant.domain.CreditProposalDetails;

public interface CreditProposalClient {

    CreditProposalDetails lookup(String cpId);
}
