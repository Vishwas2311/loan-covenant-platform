package com.crisil.covenant.formula;

import java.util.List;

/**
 * A parsed, type-checked formula. Opaque to callers outside the formula package — they obtain one
 * from {@link FormulaEngine#compile} and pass it back to evaluate/render, never touching the AST.
 */
public final class CompiledFormula {

    private final String source;
    private final transient Node ast;
    private final FormulaType type;
    private final List<String> referencedMeasures;
    private final List<FormulaTerm> terms;

    CompiledFormula(String source, Node ast, FormulaType type,
                    List<String> referencedMeasures, List<FormulaTerm> terms) {
        this.source = source;
        this.ast = ast;
        this.type = type;
        this.referencedMeasures = List.copyOf(referencedMeasures);
        this.terms = List.copyOf(terms);
    }

    public String source() {
        return source;
    }

    public FormulaType type() {
        return type;
    }

    /** Distinct measure codes referenced anywhere in the formula. */
    public List<String> referencedMeasures() {
        return referencedMeasures;
    }

    /** Every resolvable term (bare measures + aggregations) needed to evaluate the formula. */
    public List<FormulaTerm> terms() {
        return terms;
    }

    Node ast() {
        return ast;
    }
}
