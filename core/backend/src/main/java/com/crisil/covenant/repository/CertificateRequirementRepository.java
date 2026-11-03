package com.crisil.covenant.repository;

import com.crisil.covenant.entity.CertificateRequirement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** FR-22 certificate requirements per CP, oldest period first. */
@Repository
public interface CertificateRequirementRepository extends JpaRepository<CertificateRequirement, String> {

    List<CertificateRequirement> findByCpIdOrderByPeriodEndAsc(String cpId);

    Optional<CertificateRequirement> findByCpIdAndPeriodEnd(String cpId, LocalDate periodEnd);

    Optional<CertificateRequirement> findByIdAndCpId(String id, String cpId);
}
