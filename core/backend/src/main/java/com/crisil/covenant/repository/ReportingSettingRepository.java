package com.crisil.covenant.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.crisil.covenant.entity.ReportingSetting;

/**
 * US-AGG-11: persistence for the singleton {@link ReportingSetting} row. The service reads/writes
 * the single row via {@code findAll()}, so no custom query methods are needed.
 */
public interface ReportingSettingRepository extends JpaRepository<ReportingSetting, String> {
}
