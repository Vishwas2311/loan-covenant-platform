package com.crisil.covenant.repository;

import com.crisil.covenant.entity.ReminderLog;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReminderLogRepository extends JpaRepository<ReminderLog, String> {

    List<ReminderLog> findByCpIdOrderByCreatedAtDesc(String cpId);

    boolean existsByRequirementIdAndKind(String requirementId, ReminderLog.Kind kind);

    boolean existsByRequirementIdAndKindAndSentOnAfter(String requirementId, ReminderLog.Kind kind, LocalDate after);
}
