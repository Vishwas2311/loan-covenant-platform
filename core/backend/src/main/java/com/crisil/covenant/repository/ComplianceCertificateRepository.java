package com.crisil.covenant.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.ComplianceCertificate;

/**
 * CP-level compliance-certificate files (US-MON-17 AC4). Listed newest-first per CP; the scoped
 * lookup guards deletes so a caller can only remove a certificate on its own CP.
 */
@Repository
public interface ComplianceCertificateRepository extends JpaRepository<ComplianceCertificate, String> {

    List<ComplianceCertificate> findByCpIdOrderByUploadedAtDesc(String cpId);

    Optional<ComplianceCertificate> findByIdAndCpId(String id, String cpId);

    /** Production-support CP cleanup: delete every CP-level compliance-certificate row for a CP. */
    @Modifying
    @Query("delete from ComplianceCertificate c where c.cpId = :cpId")
    int deleteByCpId(@Param("cpId") String cpId);
}
