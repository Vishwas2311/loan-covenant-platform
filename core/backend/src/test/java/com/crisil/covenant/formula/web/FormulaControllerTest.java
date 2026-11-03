package com.crisil.covenant.formula.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.crisil.covenant.formula.FormulaEngine;
import com.crisil.covenant.formula.FormulaThresholdEdit;
import com.crisil.covenant.formula.FormulaType;
import com.crisil.covenant.formula.MeasureCatalog;
import com.crisil.covenant.formula.MeasureMeta;
import com.crisil.covenant.service.MeasureCatalogService;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FormulaControllerTest {

    @Mock private MeasureCatalogService catalogService;
    private final FormulaEngine engine = new FormulaEngine();
    private FormulaController controller;

    private static final MeasureCatalog CATALOG = new MeasureCatalog() {
        @Override
        public Optional<MeasureMeta> lookup(String code) {
            return "DSCR".equals(code) ? Optional.of(MeasureMeta.of("DSCR", FormulaType.NUMBER))
                    : Optional.empty();
        }

        @Override
        public Collection<String> names() {
            return List.of("DSCR");
        }
    };

    @BeforeEach
    void setUp() {
        controller = new FormulaController(engine, catalogService);
    }

    @Test
    void validFormulaReturnsSampleResult() {
        when(catalogService.activeCatalog()).thenReturn(CATALOG);
        ValidateFormulaResponse r = controller.validate(new ValidateFormulaRequest("DSCR > 1.2", null));
        assertThat(r.valid()).isTrue();
        assertThat(r.sampleResolution()).isEqualTo("2.0 > 1.2");
        assertThat(r.sampleResult()).isEqualTo("true");
    }

    @Test
    void invalidFormulaReturnsErrorsWithOffsets() {
        when(catalogService.activeCatalog()).thenReturn(CATALOG);
        ValidateFormulaResponse r = controller.validate(new ValidateFormulaRequest("DSCRR > 1", true));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).isNotEmpty();
        assertThat(r.errors().get(0).code()).isEqualTo("UNKNOWN_MEASURE");
        assertThat(r.errors().get(0).startOffset()).isEqualTo(0);
        assertThat(r.errors().get(0).endOffset()).isEqualTo(5);
    }

    @Test
    void requireBooleanFalseAllowsScalarValueFormula() {
        when(catalogService.activeCatalog()).thenReturn(CATALOG);
        ValidateFormulaResponse r = controller.validate(new ValidateFormulaRequest("DSCR", false));
        assertThat(r.valid()).isTrue();
    }

    @Test
    void metadataListsFunctionsUnitsAndSamplingKeywords() {
        FormulaMetadataResponse m = controller.metadata();
        assertThat(m.functions()).contains("AVG", "SUM", "MIN", "MAX", "LATEST", "CURRENT", "COUNT");
        assertThat(m.windowUnits()).hasSize(5);
        assertThat(m.windowUnits().get(0).symbol()).isEqualTo("d");
        assertThat(m.samplingKeywords())
                .extracting(FormulaMetadataResponse.SamplingKeywordDto::name)
                .containsExactly("FLAT", "PERIODIC", "PERIODIC_CURRENT", "PERIODIC_COMPLETED");
        assertThat(m.samplingKeywords()).allSatisfy(k -> assertThat(k.label()).isNotBlank());
    }

    @Test
    void thresholdsEndpointExtractsSlots() {
        ThresholdsResponse r = controller.thresholds(new ThresholdsRequest("DSCR > 1.5"));
        assertThat(r.thresholds()).hasSize(1);
        assertThat(r.thresholds().get(0).displayValue()).isEqualTo("1.5");
        assertThat(r.thresholds().get(0).label()).isEqualTo("DSCR");
    }

    @Test
    void applyThresholdsRebuildsAndValidates() {
        when(catalogService.activeCatalog()).thenReturn(CATALOG);
        ApplyThresholdsResponse r = controller.applyThresholds(new ApplyThresholdsRequest(
                "DSCR > 1.5",
                java.util.List.of(new FormulaThresholdEdit(7, 10, FormulaType.NUMBER, "1.8"))));
        assertThat(r.expression()).isEqualTo("DSCR > 1.8");
        assertThat(r.validation().valid()).isTrue();
        assertThat(r.validation().sampleResult()).isEqualTo("true");
    }
}
