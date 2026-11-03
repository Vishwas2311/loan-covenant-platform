package com.crisil.covenant.repository;

import com.crisil.covenant.entity.LoanSyncChange;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LoanSyncChangeRepository extends JpaRepository<LoanSyncChange, String> {

    List<LoanSyncChange> findByRunIdIn(Collection<String> runIds);
}
