package com.crisil.covenant.repository;

import com.crisil.covenant.entity.LetterLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LetterLogRepository extends JpaRepository<LetterLog, String> {

    List<LetterLog> findByCpIdOrderByCreatedAtDesc(String cpId);
}
