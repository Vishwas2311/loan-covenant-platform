package com.crisil.covenant.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.entity.CreditProposalCacheEntry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * US-MON-09 / US-MON-15 portfolio scoping: verifies {@link CreditProposalCacheRepository#findCpIdsOwnedBy}
 * against H2 in MSSQL mode (V11 migration adds {@code rm_user_id}/{@code wrm_user_id}). A user "owns" a CP
 * when they are its Relationship Manager <em>or</em> its Wholesale Relationship Manager, so the query must
 * match on either column and be null-safe for the unset side.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CreditProposalCacheRepositoryTest {

    @Autowired
    private CreditProposalCacheRepository repository;

    private static CreditProposalCacheEntry entry(String cpId, String rm, String wrm) {
        CreditProposalCacheEntry e = new CreditProposalCacheEntry();
        e.setCpId(cpId);
        e.setBusinessSegment("RETAIL");
        e.setSector("Consumer");
        e.setAttributesJson("{}");
        e.setRmUserId(rm);
        e.setWrmUserId(wrm);
        e.setFetchedAt(Instant.parse("2026-06-05T10:00:00Z"));
        return e;
    }

    @Test
    void findsCpsWhereUserIsRmOrWrm() {
        repository.saveAndFlush(entry("CP-1", "rm-anil", "wrm-meera"));
        repository.saveAndFlush(entry("CP-2", "rm-priya", "wrm-meera"));
        repository.saveAndFlush(entry("CP-3", "rm-anil", null)); // rm only, wrm null
        repository.saveAndFlush(entry("CP-4", "rm-priya", "wrm-vikram"));
        repository.saveAndFlush(entry("CP-5", null, "rm-anil")); // user is WRM here, rm null

        // rm-anil owns CP-1 & CP-3 as RM and CP-5 as WRM (OR semantics, null-safe on the unset side).
        assertThat(repository.findCpIdsOwnedBy("rm-anil"))
                .containsExactlyInAnyOrder("CP-1", "CP-3", "CP-5");
        // wrm-meera owns CP-1 & CP-2 as WRM only.
        assertThat(repository.findCpIdsOwnedBy("wrm-meera"))
                .containsExactlyInAnyOrder("CP-1", "CP-2");
    }

    @Test
    void returnsEmptyForUnknownUser() {
        repository.saveAndFlush(entry("CP-1", "rm-anil", "wrm-meera"));

        assertThat(repository.findCpIdsOwnedBy("nobody")).isEmpty();
    }
}
