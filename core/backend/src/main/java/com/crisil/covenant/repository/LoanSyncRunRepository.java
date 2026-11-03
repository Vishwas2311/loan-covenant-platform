package com.crisil.covenant.repository;

import com.crisil.covenant.entity.LoanSyncRun;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LoanSyncRunRepository extends JpaRepository<LoanSyncRun, String> {

    List<LoanSyncRun> findTop20ByOrderByStartedAtDesc();
}
