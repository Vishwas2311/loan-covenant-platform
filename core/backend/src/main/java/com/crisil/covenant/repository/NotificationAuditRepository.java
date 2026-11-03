package com.crisil.covenant.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.NotificationAudit;

@Repository
public interface NotificationAuditRepository extends JpaRepository<NotificationAudit, String> {

    List<NotificationAudit> findByRunIdOrderByChangedDateAsc(String runId);

    List<NotificationAudit> findByChangedDateBetween(Instant from, Instant to);
}
