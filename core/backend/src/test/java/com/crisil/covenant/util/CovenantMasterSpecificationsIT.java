package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.config.AuditingConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuditingConfig.class)
@ActiveProfiles("test")
class CovenantMasterSpecificationsIT {

    @Autowired
    private CovenantMasterRepository repository;

    @BeforeEach
    void seed() {
        repository.save(newEntity("DSCR check", CovenantCategory.FINANCIAL,
                "Debt service coverage", List.of("RETAIL"), true));
        repository.save(newEntity("CAR check", CovenantCategory.FINANCIAL,
                "Capital adequacy", List.of("SME"), true));
        repository.save(newEntity("Auditor check", CovenantCategory.NON_FINANCIAL,
                "Big 4 auditor required", List.of("CORPORATE", "RETAIL"), true));
        repository.save(newEntity("Inactive one", CovenantCategory.FINANCIAL,
                "old rule", List.of(), false));
    }

    @Test
    void matchesAllWhenNoFilters() {
        Page<CovenantMaster> page = repository.findAll(
                CovenantMasterSpecifications.matching(null, null, null, null),
                PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    void filtersBySearchInName() {
        Page<CovenantMaster> page = repository.findAll(
                CovenantMasterSpecifications.matching("dscr", null, null, null),
                PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(CovenantMaster::getName)
                .containsExactly("DSCR check");
    }

    @Test
    void filtersBySearchInDescription() {
        Page<CovenantMaster> page = repository.findAll(
                CovenantMasterSpecifications.matching("capital", null, null, null),
                PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(CovenantMaster::getName)
                .containsExactly("CAR check");
    }

    @Test
    void blankSearchTreatedAsNoFilter() {
        Page<CovenantMaster> page = repository.findAll(
                CovenantMasterSpecifications.matching("   ", null, null, null),
                PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    void filtersByCategory() {
        Page<CovenantMaster> page = repository.findAll(
                CovenantMasterSpecifications.matching(null, CovenantCategory.NON_FINANCIAL, null, null),
                PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void filtersByBusinessSegment() {
        Page<CovenantMaster> page = repository.findAll(
                CovenantMasterSpecifications.matching(null, null, "RETAIL", null),
                PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(CovenantMaster::getName)
                .containsExactlyInAnyOrder("DSCR check", "Auditor check");
    }

    @Test
    void filtersByActive() {
        Page<CovenantMaster> page = repository.findAll(
                CovenantMasterSpecifications.matching(null, null, null, false),
                PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getName()).isEqualTo("Inactive one");
    }

    @Test
    void combinesFilters() {
        Specification<CovenantMaster> spec = CovenantMasterSpecifications.matching(
                "check", CovenantCategory.FINANCIAL, "RETAIL", true);
        Page<CovenantMaster> page = repository.findAll(spec, PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(CovenantMaster::getName)
                .containsExactly("DSCR check");
    }

    @Test
    void blankSegmentTreatedAsNoFilter() {
        Page<CovenantMaster> page = repository.findAll(
                CovenantMasterSpecifications.matching(null, null, " ", null),
                PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    private static CovenantMaster newEntity(String name, CovenantCategory category,
                                            String description, List<String> segments, boolean active) {
        CovenantMaster e = new CovenantMaster();
        e.setName(name);
        e.setCategory(category);
        e.setDescription(description);
        e.setConditionExpression("DSCR > 1");
        e.setBusinessSegments(segments);
        e.setActive(active);
        return e;
    }
}
