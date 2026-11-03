package com.crisil.covenant.repository;

import com.crisil.covenant.entity.SavedReport;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SavedReportRepository extends JpaRepository<SavedReport, String> {

    List<SavedReport> findByOwnerOrderByNameAsc(String owner);

    List<SavedReport> findByScheduleNot(SavedReport.Schedule schedule);
}
