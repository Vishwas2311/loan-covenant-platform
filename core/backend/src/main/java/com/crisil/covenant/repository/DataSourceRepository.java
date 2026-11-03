package com.crisil.covenant.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.crisil.covenant.entity.DataSource;

public interface DataSourceRepository extends JpaRepository<DataSource, String> {

    boolean existsByNameIgnoreCase(String name);

    List<DataSource> findAllByOrderByNameAsc();

    List<DataSource> findByActiveTrueOrderByNameAsc();
}
