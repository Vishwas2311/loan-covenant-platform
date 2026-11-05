package com.crisil.covenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crisil.covenant.enums.DataColumnType;
import com.crisil.covenant.formula.FormulaType;
import com.crisil.covenant.formula.MeasureCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.repository.MeasureRepository;

@ExtendWith(MockitoExtension.class)
class MeasureCatalogServiceTest {

    @Mock private MeasureRepository repository;
    @InjectMocks private MeasureCatalogService service;

    private Measure measure(String code, DataColumnType type) {
        Measure m = new Measure();
        m.setCode(code);
        m.setDataType(type);
        return m;
    }

    @Test
    void activeCatalogExposesMeasuresWithTypes() {
        when(repository.findByActiveTrueOrderByCodeAsc()).thenReturn(List.of(
                measure("DSCR", DataColumnType.NUMBER),
                measure("auditor", DataColumnType.STRING)));
        MeasureCatalog catalog = service.activeCatalog();

        assertThat(catalog.lookup("DSCR")).isPresent();
        assertThat(catalog.lookup("DSCR").orElseThrow().type()).isEqualTo(FormulaType.NUMBER);
        assertThat(catalog.lookup("auditor").orElseThrow().type()).isEqualTo(FormulaType.STRING);
        assertThat(catalog.lookup("missing")).isEmpty();
        assertThat(catalog.names()).containsExactlyInAnyOrder("DSCR", "auditor");
    }

    @Test
    void catalogIsCachedUntilAMeasureChangesAndAgainWhenTheTransactionEnds() {
        when(repository.findByActiveTrueOrderByCodeAsc()).thenReturn(List.of(measure("DSCR", DataColumnType.NUMBER)));
        service.activeCatalog();
        service.activeCatalog();
        verify(repository, times(1)).findByActiveTrueOrderByCodeAsc();

        new MeasureChangeListener().changed(measure("NEW", DataColumnType.NUMBER));
        service.activeCatalog();
        verify(repository, times(2)).findByActiveTrueOrderByCodeAsc();

        TransactionSynchronizationManager.initSynchronization();
        try {
            MeasureCatalogService.measuresChanged();
            service.activeCatalog(); // read inside the writing transaction
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        service.activeCatalog(); // the rolled-back snapshot is not reused
        verify(repository, times(4)).findByActiveTrueOrderByCodeAsc();
    }

    @Test
    void mapsEveryDataColumnType() {
        assertThat(MeasureCatalogService.toFormulaType(DataColumnType.NUMBER)).isEqualTo(FormulaType.NUMBER);
        assertThat(MeasureCatalogService.toFormulaType(DataColumnType.STRING)).isEqualTo(FormulaType.STRING);
        assertThat(MeasureCatalogService.toFormulaType(DataColumnType.DATE)).isEqualTo(FormulaType.DATE);
        assertThat(MeasureCatalogService.toFormulaType(DataColumnType.BOOLEAN)).isEqualTo(FormulaType.BOOLEAN);
    }
}
