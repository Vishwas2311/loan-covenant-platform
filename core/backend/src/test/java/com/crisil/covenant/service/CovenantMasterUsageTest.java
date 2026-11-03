package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.crisil.covenant.repository.AppliedCovenantRepository;
import com.crisil.covenant.enums.AppliedCovenantStatus;
import com.crisil.covenant.enums.CovenantCategory;
import com.crisil.covenant.exception.ResourceNotFoundException;
import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.repository.ExpressionChangeLogRepository;
import com.crisil.covenant.service.MeasureCatalogService;
import com.crisil.covenant.dto.CovenantUsageResponse;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.crisil.covenant.entity.CovenantMaster;
import com.crisil.covenant.repository.CovenantMasterRepository;

@ExtendWith(MockitoExtension.class)
class CovenantMasterUsageTest {

    @Mock private CovenantMasterRepository repository;
    @Mock private FormulaEngine formulaEngine;
    @Mock private MeasureCatalogService measureCatalog;
    @Mock private ExpressionChangeLogRepository changeLogRepository;
    @Mock private AppliedCovenantRepository appliedRepository;

    @InjectMocks private CovenantMasterService service;

    @Test
    void usageReturnsCountsAndCpIds() {
        when(repository.findById("1")).thenReturn(Optional.of(newCovenant("1")));
        when(appliedRepository.countByCovenantMasterId("1")).thenReturn(3L);
        when(appliedRepository.countByCovenantMasterIdAndStatus("1", AppliedCovenantStatus.ACTIVE))
                .thenReturn(2L);
        when(appliedRepository.findCpIdsByCovenantMasterId("1"))
                .thenReturn(List.of("CP-1001", "CP-1002", "CP-1003"));

        CovenantUsageResponse usage = service.usage("1");

        assertThat(usage.covenantId()).isEqualTo("1");
        assertThat(usage.totalApplied()).isEqualTo(3L);
        assertThat(usage.activelyApplied()).isEqualTo(2L);
        assertThat(usage.sampleCpIds()).containsExactly("CP-1001", "CP-1002", "CP-1003");
    }

    @Test
    void usageCapsSampleAt25() {
        when(repository.findById("1")).thenReturn(Optional.of(newCovenant("1")));
        when(appliedRepository.countByCovenantMasterId("1")).thenReturn(100L);
        when(appliedRepository.countByCovenantMasterIdAndStatus("1", AppliedCovenantStatus.ACTIVE))
                .thenReturn(80L);
        List<String> hundred = IntStream.range(0, 100)
                .mapToObj(i -> "CP-" + i)
                .collect(Collectors.toList());
        when(appliedRepository.findCpIdsByCovenantMasterId("1")).thenReturn(hundred);

        CovenantUsageResponse usage = service.usage("1");

        assertThat(usage.sampleCpIds()).hasSize(25);
    }

    @Test
    void usageMissingCovenantThrows404() {
        when(repository.findById("99")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.usage("99"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static CovenantMaster newCovenant(String id) {
        CovenantMaster e = new CovenantMaster();
        try {
            Field f = CovenantMaster.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        e.setName("DSCR");
        e.setCategory(CovenantCategory.FINANCIAL);
        e.setConditionExpression("DSCR > 1");
        e.setReferencedMeasures(List.of("DSCR"));
        e.setBusinessSegments(List.of());
        e.setActive(true);
        return e;
    }
}
