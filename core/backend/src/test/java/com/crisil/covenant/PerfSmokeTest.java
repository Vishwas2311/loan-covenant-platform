package com.crisil.covenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;
import com.crisil.covenant.util.CovenantMasterSpecifications;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PerfSmokeTest {

    @Autowired
    private CovenantMasterRepository repository;

    @Test
    void searchOver500CovenantsCompletesUnder500ms() {
        List<CovenantMaster> batch = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            CovenantMaster e = new CovenantMaster();
            e.setName("Perf-" + i);
            e.setCategory(i % 2 == 0 ? CovenantCategory.FINANCIAL : CovenantCategory.NON_FINANCIAL);
            e.setConditionExpression("DSCR > 1");
            e.setBusinessSegments(i % 4 == 0 ? List.of("RETAIL") : List.of("SME"));
            e.setActive(true);
            batch.add(e);
        }
        repository.saveAll(batch);

        // warm-up
        repository.findAll(CovenantMasterSpecifications.matching(null, null, null, null),
                PageRequest.of(0, 1));

        long t0 = System.nanoTime();
        var page = repository.findAll(
                CovenantMasterSpecifications.matching(null, CovenantCategory.FINANCIAL,
                        "RETAIL", true),
                PageRequest.of(0, 25));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(page.getContent()).isNotEmpty();
        assertThat(elapsedMs)
                .as("search over 500 covenants completed in " + elapsedMs + "ms — warm budget 500ms")
                .isLessThan(500);
    }
}
