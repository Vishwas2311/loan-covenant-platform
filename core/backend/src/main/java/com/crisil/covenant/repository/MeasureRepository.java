package com.crisil.covenant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.Measure;

@Repository
public interface MeasureRepository extends JpaRepository<Measure, String> {

    boolean existsByCodeIgnoreCase(String code);

    @EntityGraph(Measure.WITH_TABLE_AND_SOURCE)
    Optional<Measure> findByCodeIgnoreCase(String code);

    @EntityGraph(Measure.WITH_TABLE_AND_SOURCE)
    Optional<Measure> findById(String id);

    @EntityGraph(Measure.WITH_TABLE_AND_SOURCE)
    List<Measure> findByActiveTrueOrderByCodeAsc();

    @EntityGraph(Measure.WITH_TABLE_AND_SOURCE)
    List<Measure> findByCodeIn(Collection<String> codes);

    @EntityGraph(Measure.WITH_TABLE_AND_SOURCE)
    List<Measure> findAllByOrderByCodeAsc();

    /** All measures backed by a table — the set of columns/codes to copy when staging that table. */
    List<Measure> findByDataSourceTableId(String dataSourceTableId);

    long countByDataSourceTableId(String dataSourceTableId);
}
