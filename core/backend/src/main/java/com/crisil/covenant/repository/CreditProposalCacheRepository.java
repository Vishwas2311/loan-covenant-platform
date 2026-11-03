package com.crisil.covenant.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.crisil.covenant.entity.CreditProposalCacheEntry;

@Repository
public interface CreditProposalCacheRepository extends JpaRepository<CreditProposalCacheEntry, String> {

    /** Production-support CP cleanup: evict the cached CP-attribute row (keyed by cp_id). */
    @Modifying
    @Query("delete from CreditProposalCacheEntry e where e.cpId = :cpId")
    int deleteByCpId(@Param("cpId") String cpId);

    /**
     * US-MON-09 / US-MON-15 portfolio scoping: the CP ids owned by {@code user}, i.e. those on which
     * the user is the Relationship Manager <em>or</em> the Wholesale Relationship Manager. Callers must
     * guard against a blank user (a blank would never equal a stored id, but the service short-circuits
     * to an empty scope before querying).
     */
    @Query("select e.cpId from CreditProposalCacheEntry e where e.rmUserId = :u or e.wrmUserId = :u")
    List<String> findCpIdsOwnedBy(@Param("u") String user);
}
