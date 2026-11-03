package com.crisil.covenant.repository;

import com.crisil.covenant.entity.AccessKey;
import com.crisil.covenant.entity.PortalAccess;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PortalAccessRepository extends JpaRepository<PortalAccess, AccessKey> {

    @Query("select a from PortalAccess a where a.key.userId = :u order by a.key.cpId")
    List<PortalAccess> findByUser(@Param("u") String userId);

    @Query("select a from PortalAccess a where a.key.cpId = :cp order by a.key.userId")
    List<PortalAccess> findByCp(@Param("cp") String cpId);
}
