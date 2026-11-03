package com.crisil.covenant.repository;

import com.crisil.covenant.entity.PortalSubmission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PortalSubmissionRepository extends JpaRepository<PortalSubmission, String> {

    List<PortalSubmission> findByCpIdOrderBySubmittedAtDesc(String cpId);
}
