package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.crisil.covenant.domain.CovenantAttributes;
import com.crisil.covenant.domain.CpUser;
import com.crisil.covenant.domain.CreditProposalDetails;
import com.crisil.covenant.entity.CreditProposalCacheEntry;
import com.crisil.covenant.repository.CreditProposalCacheRepository;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class CachedCovenantAttributesServiceTest {

    @Mock private CovenantAttributesClient client;
    @Mock private CreditProposalCacheRepository repo;
    @Mock private CreditProposalClient creditProposalClient;

    @Test
    void firstCallFetchesFromUpstreamAndStoresCacheEntry() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-05T10:00:00Z"), ZoneOffset.UTC);
        CachedCovenantAttributesService svc = new CachedCovenantAttributesService(client, repo, creditProposalClient, clock);
        when(repo.findById("CP-1")).thenReturn(Optional.empty());
        when(client.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "RETAIL", "Consumer", Map.of("k", "v")));

        CovenantAttributes result = svc.fetch("CP-1");

        assertThat(result.businessSegment()).isEqualTo("RETAIL");
        ArgumentCaptor<CreditProposalCacheEntry> captor =
                ArgumentCaptor.forClass(CreditProposalCacheEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getCpId()).isEqualTo("CP-1");
        assertThat(captor.getValue().getBusinessSegment()).isEqualTo("RETAIL");
        assertThat(captor.getValue().getAttributesJson()).contains("\"k\":\"v\"");
        assertThat(captor.getValue().getFetchedAt()).isEqualTo(Instant.parse("2026-06-05T10:00:00Z"));
    }

    @Test
    void freshCacheHitDoesNotCallUpstream() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-05T10:30:00Z"), ZoneOffset.UTC);
        CachedCovenantAttributesService svc = new CachedCovenantAttributesService(client, repo, creditProposalClient, clock);
        CreditProposalCacheEntry entry = new CreditProposalCacheEntry();
        entry.setCpId("CP-1");
        entry.setBusinessSegment("RETAIL");
        entry.setSector("Consumer");
        entry.setAttributesJson("{\"k\":\"v\"}");
        entry.setFetchedAt(Instant.parse("2026-06-05T10:00:00Z")); // 30 min old, within 1h TTL
        when(repo.findById("CP-1")).thenReturn(Optional.of(entry));

        CovenantAttributes result = svc.fetch("CP-1");

        assertThat(result.businessSegment()).isEqualTo("RETAIL");
        assertThat(result.safeAttributes()).containsEntry("k", "v");
        verify(client, never()).fetch("CP-1");
        verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expiredCacheTriggersUpstreamRefresh() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC);
        CachedCovenantAttributesService svc = new CachedCovenantAttributesService(client, repo, creditProposalClient, clock);
        CreditProposalCacheEntry entry = new CreditProposalCacheEntry();
        entry.setCpId("CP-1");
        entry.setBusinessSegment("OLD");
        entry.setSector("Old");
        entry.setAttributesJson("{}");
        entry.setFetchedAt(Instant.parse("2026-06-05T10:00:00Z")); // 2h old → stale
        when(repo.findById("CP-1")).thenReturn(Optional.of(entry));
        when(client.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "NEW", "New", Map.of("k", "v2")));

        CovenantAttributes result = svc.fetch("CP-1");

        assertThat(result.businessSegment()).isEqualTo("NEW");
        verify(client, times(1)).fetch("CP-1");
        verify(repo).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void corruptCachedJsonFallsBackToEmptyMap() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-05T10:30:00Z"), ZoneOffset.UTC);
        CachedCovenantAttributesService svc = new CachedCovenantAttributesService(client, repo, creditProposalClient, clock);
        CreditProposalCacheEntry entry = new CreditProposalCacheEntry();
        entry.setCpId("CP-1");
        entry.setBusinessSegment("RETAIL");
        entry.setSector("Consumer");
        entry.setAttributesJson("not-json");
        entry.setFetchedAt(Instant.parse("2026-06-05T10:00:00Z"));
        when(repo.findById("CP-1")).thenReturn(Optional.of(entry));

        CovenantAttributes result = svc.fetch("CP-1");
        assertThat(result.safeAttributes()).isEmpty();
    }

    @Test
    void persistsRmAndWrmFromCpLookup() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-05T10:00:00Z"), ZoneOffset.UTC);
        CachedCovenantAttributesService svc =
                new CachedCovenantAttributesService(client, repo, creditProposalClient, clock);
        when(repo.findById("CP-1")).thenReturn(Optional.empty());
        when(client.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "RETAIL", "Consumer", Map.of("k", "v")));
        when(creditProposalClient.lookup("CP-1")).thenReturn(
                new CreditProposalDetails("CP-1", "Acme", "RETAIL", "Consumer", "APPROVED",
                        List.of(), new CpUser("rm-anil", "Anil", null),
                        new CpUser("wrm-meera", "Meera", null), null, null));

        svc.fetch("CP-1");

        ArgumentCaptor<CreditProposalCacheEntry> captor =
                ArgumentCaptor.forClass(CreditProposalCacheEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getRmUserId()).isEqualTo("rm-anil");
        assertThat(captor.getValue().getWrmUserId()).isEqualTo("wrm-meera");
    }

    @Test
    void lookupFailurePreservesPriorOwnersAndStillCachesAttributes() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC);
        CachedCovenantAttributesService svc =
                new CachedCovenantAttributesService(client, repo, creditProposalClient, clock);
        CreditProposalCacheEntry entry = new CreditProposalCacheEntry();
        entry.setCpId("CP-1");
        entry.setBusinessSegment("OLD");
        entry.setSector("Old");
        entry.setAttributesJson("{}");
        entry.setRmUserId("rm-anil");
        entry.setWrmUserId("wrm-meera");
        entry.setFetchedAt(Instant.parse("2026-06-05T10:00:00Z")); // 2h old → stale
        when(repo.findById("CP-1")).thenReturn(Optional.of(entry));
        when(client.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "NEW", "New", Map.of("k", "v2")));
        when(creditProposalClient.lookup("CP-1")).thenThrow(new RuntimeException("upstream down"));

        CovenantAttributes result = svc.fetch("CP-1");

        assertThat(result.businessSegment()).isEqualTo("NEW");
        ArgumentCaptor<CreditProposalCacheEntry> captor =
                ArgumentCaptor.forClass(CreditProposalCacheEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getBusinessSegment()).isEqualTo("NEW");
        assertThat(captor.getValue().getRmUserId()).isEqualTo("rm-anil");
        assertThat(captor.getValue().getWrmUserId()).isEqualTo("wrm-meera");
    }

    @Test
    void nullOwnersFromLookupPreservePriorOwners() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC);
        CachedCovenantAttributesService svc =
                new CachedCovenantAttributesService(client, repo, creditProposalClient, clock);
        CreditProposalCacheEntry entry = new CreditProposalCacheEntry();
        entry.setCpId("CP-1");
        entry.setBusinessSegment("OLD");
        entry.setSector("Old");
        entry.setAttributesJson("{}");
        entry.setRmUserId("rm-anil");
        entry.setWrmUserId("wrm-meera");
        entry.setFetchedAt(Instant.parse("2026-06-05T10:00:00Z")); // 2h old → stale
        when(repo.findById("CP-1")).thenReturn(Optional.of(entry));
        when(client.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "NEW", "New", Map.of("k", "v2")));
        when(creditProposalClient.lookup("CP-1")).thenReturn(
                new CreditProposalDetails("CP-1", "Acme", "NEW", "New", "APPROVED",
                        List.of(), null, null, null, null));

        svc.fetch("CP-1");

        ArgumentCaptor<CreditProposalCacheEntry> captor =
                ArgumentCaptor.forClass(CreditProposalCacheEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getRmUserId()).isEqualTo("rm-anil");
        assertThat(captor.getValue().getWrmUserId()).isEqualTo("wrm-meera");
    }

    @Test
    void blankOrNullOwnerIdsFromLookupPreservePriorOwners() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC);
        CachedCovenantAttributesService svc =
                new CachedCovenantAttributesService(client, repo, creditProposalClient, clock);
        CreditProposalCacheEntry entry = new CreditProposalCacheEntry();
        entry.setCpId("CP-1");
        entry.setBusinessSegment("OLD");
        entry.setSector("Old");
        entry.setAttributesJson("{}");
        entry.setRmUserId("rm-anil");
        entry.setWrmUserId("wrm-meera");
        entry.setFetchedAt(Instant.parse("2026-06-05T10:00:00Z")); // 2h old → stale
        when(repo.findById("CP-1")).thenReturn(Optional.of(entry));
        when(client.fetch("CP-1")).thenReturn(
                new CovenantAttributes("CP-1", "NEW", "New", Map.of("k", "v2")));
        // Blank rm userId and null wrm userId are both treated as "unknown" → prior owners preserved.
        when(creditProposalClient.lookup("CP-1")).thenReturn(
                new CreditProposalDetails("CP-1", "Acme", "NEW", "New", "APPROVED",
                        List.of(), new CpUser("  ", "Blank", null),
                        new CpUser(null, "Nully", null), null, null));

        svc.fetch("CP-1");

        ArgumentCaptor<CreditProposalCacheEntry> captor =
                ArgumentCaptor.forClass(CreditProposalCacheEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getRmUserId()).isEqualTo("rm-anil");
        assertThat(captor.getValue().getWrmUserId()).isEqualTo("wrm-meera");
    }

    @Test
    void cacheEntryGettersAndSetters() {
        CreditProposalCacheEntry e = new CreditProposalCacheEntry();
        e.setCpId("CP-1");
        e.setBusinessSegment("R");
        e.setSector("S");
        e.setAttributesJson("{}");
        e.setFetchedAt(Instant.parse("2026-06-05T10:00:00Z"));
        assertThat(e.getCpId()).isEqualTo("CP-1");
        assertThat(e.getBusinessSegment()).isEqualTo("R");
        assertThat(e.getSector()).isEqualTo("S");
        assertThat(e.getAttributesJson()).isEqualTo("{}");
        assertThat(e.getFetchedAt()).isEqualTo(Instant.parse("2026-06-05T10:00:00Z"));
    }
}
