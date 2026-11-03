package com.crisil.covenant.formula.web;

import com.crisil.covenant.formula.FunctionKind;
import com.crisil.covenant.formula.SamplingKeyword;
import java.util.List;

/**
 * Grammar metadata that drives the builder palette: the aggregation functions, window units, and the
 * US-AGG-11 sampling keywords (the optional third aggregation argument).
 */
public record FormulaMetadataResponse(
        List<String> functions,
        List<WindowUnitDto> windowUnits,
        List<SamplingKeywordDto> samplingKeywords) {

    public record WindowUnitDto(String symbol, String label) {
    }

    /** A sampling keyword the author may write, e.g. {@code PERIODIC}, with a builder-palette label. */
    public record SamplingKeywordDto(String name, String label) {
    }

    public static FormulaMetadataResponse build() {
        List<String> functions = java.util.Arrays.stream(FunctionKind.values())
                .map(Enum::name).toList();
        List<WindowUnitDto> units = List.of(
                new WindowUnitDto("d", "Days"),
                new WindowUnitDto("w", "Weeks"),
                new WindowUnitDto("m", "Months"),
                new WindowUnitDto("q", "Quarters"),
                new WindowUnitDto("y", "Years"));
        List<SamplingKeywordDto> sampling = java.util.Arrays.stream(SamplingKeyword.values())
                .map(k -> new SamplingKeywordDto(k.name(), k.label()))
                .toList();
        return new FormulaMetadataResponse(functions, units, sampling);
    }
}
