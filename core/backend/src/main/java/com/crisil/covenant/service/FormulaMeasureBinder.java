package com.crisil.covenant.service;

import com.crisil.covenant.entity.Measure;
import com.crisil.covenant.formula.CompiledFormula;
import com.crisil.covenant.formula.FormulaException;
import com.crisil.covenant.formula.FormulaTerm;
import com.crisil.covenant.repository.MeasureRepository;
import com.crisil.covenant.service.MeasureAggregationResolver.AggregationRequest;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

/**
 * Binds a compiled formula's terms to the {@code cov_measure} rows they reference. Extracted from
 * {@code AssessmentService} so evaluation and production-support diagnostics resolve measures through
 * exactly the same lookup and can never diverge on "which measure backs this term".
 */
@Component
public class FormulaMeasureBinder {

    private final MeasureRepository measureRepository;

    public FormulaMeasureBinder(MeasureRepository measureRepository) {
        this.measureRepository = measureRepository;
    }

    /**
     * Map each formula term to a resolvable {@link AggregationRequest}, looking up the measure entity by
     * code. Throws {@link FormulaException} if a referenced measure is not registered — the behaviour
     * evaluation relies on. Diagnostics uses {@link #measuresByCode} instead so it can classify a
     * missing measure rather than abort.
     */
    public List<AggregationRequest> bind(CompiledFormula compiled) {
        Map<String, Measure> byCode = measuresByCode(compiled.referencedMeasures());
        List<AggregationRequest> requests = new ArrayList<>();
        for (FormulaTerm term : compiled.terms()) {
            Measure measure = byCode.get(term.measure());
            if (measure == null) {
                throw new FormulaException("Measure '" + term.measure() + "' is not registered");
            }
            requests.add(new AggregationRequest(
                    measure, term.aggregation(), term.window(),
                    term.sampling(), term.inclusion(), term.valueKey()));
        }
        return requests;
    }

    /** The registered measures for the given codes, keyed by code (missing codes are simply absent). */
    public Map<String, Measure> measuresByCode(Collection<String> codes) {
        Map<String, Measure> byCode = new LinkedHashMap<>();
        for (Measure measure : measureRepository.findByCodeIn(codes)) {
            byCode.put(measure.getCode(), measure);
        }
        return byCode;
    }
}
