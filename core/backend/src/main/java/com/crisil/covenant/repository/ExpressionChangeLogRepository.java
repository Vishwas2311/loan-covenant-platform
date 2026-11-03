package com.crisil.covenant.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.ExpressionChangeLog;

@Repository
public interface ExpressionChangeLogRepository extends JpaRepository<ExpressionChangeLog, String> {

    List<ExpressionChangeLog> findByCovenantIdOrderByChangedDateDesc(String covenantId);
}
