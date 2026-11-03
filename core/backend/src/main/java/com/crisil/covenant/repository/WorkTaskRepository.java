package com.crisil.covenant.repository;

import com.crisil.covenant.entity.WorkTask;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkTaskRepository extends JpaRepository<WorkTask, String> {

    List<WorkTask> findByCpIdAndStatus(String cpId, WorkTask.Status status);

    List<WorkTask> findByCpIdInOrderByDueDateAsc(Collection<String> cpIds);
}
