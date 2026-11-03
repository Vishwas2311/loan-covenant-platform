package com.crisil.covenant.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.DataSourceTable;

@Repository
public interface DataSourceTableRepository extends JpaRepository<DataSourceTable, String> {

    @EntityGraph(DataSourceTable.WITH_SOURCE)
    List<DataSourceTable> findByDataSourceIdOrderByNameAsc(String dataSourceId);

    @EntityGraph(DataSourceTable.WITH_SOURCE)
    Optional<DataSourceTable> findById(String id);

    boolean existsByDataSourceIdAndNameIgnoreCase(String dataSourceId, String name);

    long countByDataSourceId(String dataSourceId);
}
