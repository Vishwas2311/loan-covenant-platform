package com.crisil.covenant.repository;

import com.crisil.covenant.entity.DataEvent;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DataEventRepository extends JpaRepository<DataEvent, String> {

    boolean existsByDataSourceIdAndEntityKeyAndChangedAt(String dataSourceId, String entityKey, Instant changedAt);
}
