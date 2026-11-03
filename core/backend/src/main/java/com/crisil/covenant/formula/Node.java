package com.crisil.covenant.formula;

import java.time.LocalDate;

/**
 * The formula abstract syntax tree. The hierarchy is sealed and package-private: only the engine
 * components (lexer/parser/type-checker/evaluator/renderer) touch nodes. External callers work
 * through {@link FormulaEngine} and the opaque {@link CompiledFormula}.
 *
 * <p>Every node carries the {@code [start, end)} source offsets so errors and the auto-derived
 * "tracked value" expression can be sliced back out of the original text.
 */
sealed interface Node permits
        Node.NumberLiteral,
        Node.StringLiteral,
        Node.DateLiteral,
        Node.BoolLiteral,
        Node.MeasureRef,
        Node.Aggregation,
        Node.Unary,
        Node.Binary {

    int start();

    int end();

    /** A numeric literal. {@code raw} preserves the source text; {@code wasPercent} divides by 100. */
    record NumberLiteral(double value, boolean wasPercent, String raw, int start, int end)
            implements Node {
    }

    record StringLiteral(String value, int start, int end) implements Node {
    }

    record DateLiteral(LocalDate value, int start, int end) implements Node {
    }

    record BoolLiteral(boolean value, int start, int end) implements Node {
    }

    /** A bare reference to a measure, resolved to its as-of value. */
    record MeasureRef(String name, int start, int end) implements Node {
    }

    /**
     * An aggregation {@code FUNC(measure, window[, sampling])} over a time window. {@code sampling}
     * (default {@link SamplingMode#FLAT}) and {@code inclusion} (default {@link PeriodInclusion#DEFAULT})
     * capture the optional third keyword argument (US-AGG-11).
     */
    record Aggregation(FunctionKind function, MeasureRef measure, Window window,
                       SamplingMode sampling, PeriodInclusion inclusion, int start, int end)
            implements Node {
    }

    record Unary(UnaryOp op, Node operand, int start, int end) implements Node {
    }

    record Binary(BinaryOperator op, Node left, Node right, int start, int end) implements Node {
    }
}
