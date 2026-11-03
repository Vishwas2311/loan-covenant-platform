package com.crisil.covenant.formula;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The public entry point to the measure formula language. Replaces the SpEL-based
 * {@code SpelRuleValidator}: it lexes, parses, type-checks, sample-evaluates, compiles, evaluates,
 * and renders formulas over named measures.
 *
 * <p>Inherently safer than SpEL — the grammar has no production for method calls, type references,
 * property navigation, or bean access, so there is no reflection surface to deny. Inputs are
 * bounded by length ({@value #MAX_LENGTH}), nesting depth (in the parser), and term count
 * ({@value #MAX_TERMS}).
 */
@Component
public class FormulaEngine {

    static final int MAX_LENGTH = 2000;
    static final int MAX_TERMS = 64;

    private final FormulaTypeChecker typeChecker = new FormulaTypeChecker();
    private final FormulaEvaluator evaluator = new FormulaEvaluator();
    private final FormulaRenderer renderer = new FormulaRenderer();

    /**
     * Validate a formula while typing. Never throws — returns errors (with offsets) or, when
     * valid, a sample resolution + dry-run result.
     *
     * @param requireBoolean when {@code true} the top-level result must be boolean (a covenant
     *                       condition); when {@code false} any scalar is accepted (a tracked value)
     */
    public FormulaValidationResult validate(String formula, MeasureCatalog catalog, boolean requireBoolean) {
        if (formula == null || formula.isBlank()) {
            return FormulaValidationResult.fail(
                    new FormulaError(ErrorCode.EMPTY_FORMULA, "Formula must not be empty", 0, 0));
        }
        if (formula.length() > MAX_LENGTH) {
            return FormulaValidationResult.fail(new FormulaError(ErrorCode.FORMULA_TOO_LONG,
                    "Formula exceeds " + MAX_LENGTH + " characters", 0, formula.length()));
        }
        Node ast;
        try {
            ast = parse(formula);
        } catch (FormulaSyntaxException e) {
            return FormulaValidationResult.fail(e.toError());
        }

        List<FormulaTerm> terms = collectTerms(ast);
        List<FormulaError> errors = new ArrayList<>();
        if (terms.size() > MAX_TERMS) {
            errors.add(new FormulaError(ErrorCode.TOO_MANY_TERMS,
                    "Formula references too many measures (max " + MAX_TERMS + ")", 0, formula.length()));
        }
        FormulaType type = typeChecker.check(ast, catalog, errors);
        if (errors.isEmpty() && requireBoolean && type != FormulaType.BOOLEAN) {
            errors.add(new FormulaError(ErrorCode.NON_BOOLEAN_CONDITION,
                    "Condition must be a true/false test, e.g. DSCR > 1.2", ast.start(), ast.end()));
        }
        if (!errors.isEmpty()) {
            return FormulaValidationResult.fail(errors);
        }

        Map<String, Object> sample = buildSample(terms, catalog);
        try {
            Object result = evaluator.eval(ast, sample);
            return FormulaValidationResult.ok(renderer.render(ast, sample), FormulaRenderer.format(result));
        } catch (FormulaException ex) {
            return FormulaValidationResult.fail(
                    new FormulaError(ErrorCode.EVAL_ERROR, ex.getMessage(), ast.start(), ast.end()));
        }
    }

    /**
     * Parse + type-check a formula expected to be valid (e.g. a saved covenant). Throws
     * {@link FormulaException} with the combined messages if it is not.
     */
    public CompiledFormula compile(String formula, MeasureCatalog catalog) {
        Node ast;
        try {
            ast = parse(formula == null ? "" : formula);
        } catch (FormulaSyntaxException e) {
            throw new FormulaException(e.getMessage());
        }
        List<FormulaError> errors = new ArrayList<>();
        FormulaType type = typeChecker.check(ast, catalog, errors);
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (FormulaError error : errors) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(error.message());
            }
            throw new FormulaException(sb.toString());
        }
        List<FormulaTerm> terms = collectTerms(ast);
        List<String> measures = new ArrayList<>();
        for (FormulaTerm term : terms) {
            if (!measures.contains(term.measure())) {
                measures.add(term.measure());
            }
        }
        return new CompiledFormula(formula, ast, type, measures, terms);
    }

    /**
     * Evaluate a compiled boolean formula. Throws {@link FormulaException} if a referenced value is
     * missing or the result is not boolean — callers ensure values are present (the resolver routes
     * missing values to their own error path) and map the exception to an ERROR assessment.
     */
    public boolean evaluate(CompiledFormula formula, Map<String, Object> values) {
        Object raw = evaluateRaw(formula, values);
        if (!(raw instanceof Boolean b)) {
            throw new FormulaException("Formula did not evaluate to true/false");
        }
        return b;
    }

    /** Evaluate a compiled formula to its raw result (any type). */
    public Object evaluateRaw(CompiledFormula formula, Map<String, Object> values) {
        return evaluator.eval(formula.ast(), values == null ? Map.of() : values);
    }

    /** Render a compiled formula with values substituted, for the audit trail. */
    public String renderResolved(CompiledFormula formula, Map<String, Object> values) {
        return renderer.render(formula.ast(), values == null ? Map.of() : values);
    }

    /**
     * The scalar sub-expression worth tracking as the covenant's "value" — the left side of the
     * first comparison, or the whole formula when it is itself a single measure/aggregation.
     */
    public Optional<String> trackedValueExpression(CompiledFormula formula) {
        return firstValueNode(formula.ast())
                .map(n -> formula.source().substring(n.start(), n.end()));
    }

    /**
     * Extract the editable thresholds of a condition: each literal that is one side of a
     * comparison (the other side being a measure/expression). Parse-only — does not require the
     * measures to be known — so it works on any structurally valid formula. Returns empty if the
     * formula does not parse.
     */
    public List<FormulaThreshold> thresholds(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        Node ast;
        try {
            ast = parse(expression);
        } catch (FormulaSyntaxException e) {
            return List.of();
        }
        List<FormulaThreshold> out = new ArrayList<>();
        collectThresholds(ast, expression, out);
        return out;
    }

    /** Rebuild a condition by replacing the literals named by {@code edits} with new values. */
    public String applyThresholds(String source, List<FormulaThresholdEdit> edits) {
        if (source == null) {
            return "";
        }
        List<FormulaThresholdEdit> ordered = new ArrayList<>(edits);
        ordered.sort((a, b) -> Integer.compare(b.startOffset(), a.startOffset())); // splice right-to-left
        StringBuilder sb = new StringBuilder(source);
        for (FormulaThresholdEdit edit : ordered) {
            if (edit.startOffset() < 0 || edit.endOffset() > sb.length()
                    || edit.startOffset() > edit.endOffset()) {
                continue;
            }
            sb.replace(edit.startOffset(), edit.endOffset(), formatEdit(edit));
        }
        return sb.toString();
    }

    // -- internals -------------------------------------------------------

    private static Node parse(String formula) {
        return new Parser(new Lexer(formula).tokenize()).parse();
    }

    private static List<FormulaTerm> collectTerms(Node ast) {
        Map<String, FormulaTerm> byKey = new LinkedHashMap<>();
        collectTerms(ast, byKey);
        return new ArrayList<>(byKey.values());
    }

    private static void collectTerms(Node node, Map<String, FormulaTerm> byKey) {
        if (node instanceof Node.MeasureRef m) {
            String key = FormulaKeys.key(m);
            byKey.putIfAbsent(key, new FormulaTerm(m.name(), null, null, key));
        } else if (node instanceof Node.Aggregation a) {
            String key = FormulaKeys.key(a);
            byKey.putIfAbsent(key, new FormulaTerm(a.measure().name(), a.function(), a.window(),
                    a.sampling(), a.inclusion(), key));
        } else if (node instanceof Node.Unary u) {
            collectTerms(u.operand(), byKey);
        } else if (node instanceof Node.Binary b) {
            collectTerms(b.left(), byKey);
            collectTerms(b.right(), byKey);
        }
        // literals contribute no terms
    }

    private static Map<String, Object> buildSample(List<FormulaTerm> terms, MeasureCatalog catalog) {
        Map<String, Object> sample = new LinkedHashMap<>();
        for (FormulaTerm term : terms) {
            Optional<MeasureMeta> meta = catalog.lookup(term.measure());
            FormulaType measureType = meta.map(MeasureMeta::type).orElse(FormulaType.NUMBER);
            if (term.isAggregation()) {
                sample.put(term.valueKey(), defaultSample(term.aggregation().resultType(measureType)));
            } else {
                Object explicit = meta.map(MeasureMeta::sampleValue).orElse(null);
                sample.put(term.valueKey(), explicit != null ? explicit : defaultSample(measureType));
            }
        }
        return sample;
    }

    private static Object defaultSample(FormulaType type) {
        return switch (type) {
            case NUMBER -> 2.0;
            case STRING -> "sample";
            case DATE -> LocalDate.now();
            case BOOLEAN -> Boolean.TRUE;
        };
    }

    private static Optional<Node> firstValueNode(Node node) {
        if (node instanceof Node.Binary b) {
            if (b.op().category() == BinaryOperator.Category.COMPARISON
                    || b.op().category() == BinaryOperator.Category.EQUALITY) {
                return Optional.of(b.left());
            }
            return firstValueNode(b.left()).or(() -> firstValueNode(b.right()));
        }
        if (node instanceof Node.Unary u) {
            return firstValueNode(u.operand());
        }
        if (node instanceof Node.MeasureRef || node instanceof Node.Aggregation) {
            return Optional.of(node);
        }
        return Optional.empty();
    }

    private static void collectThresholds(Node node, String source, List<FormulaThreshold> out) {
        if (node instanceof Node.Binary b) {
            BinaryOperator.Category cat = b.op().category();
            if (cat == BinaryOperator.Category.COMPARISON || cat == BinaryOperator.Category.EQUALITY) {
                Node literal = literalOperand(b.left(), b.right());
                if (literal != null) {
                    Node other = literal == b.left() ? b.right() : b.left();
                    out.add(new FormulaThreshold(
                            literal.start(), literal.end(),
                            thresholdType(literal),
                            displayValue(literal),
                            source.substring(other.start(), other.end()),
                            b.op().symbol()));
                }
            }
            collectThresholds(b.left(), source, out);
            collectThresholds(b.right(), source, out);
        } else if (node instanceof Node.Unary u) {
            collectThresholds(u.operand(), source, out);
        }
    }

    /** The literal operand when exactly one side is a literal value; otherwise null. */
    private static Node literalOperand(Node left, Node right) {
        boolean l = isLiteralValue(left);
        boolean r = isLiteralValue(right);
        if (l && !r) {
            return left;
        }
        if (r && !l) {
            return right;
        }
        return null;
    }

    private static boolean isLiteralValue(Node node) {
        if (node instanceof Node.NumberLiteral || node instanceof Node.StringLiteral
                || node instanceof Node.BoolLiteral || node instanceof Node.DateLiteral) {
            return true;
        }
        return node instanceof Node.Unary u && u.op() == UnaryOp.NEGATE
                && u.operand() instanceof Node.NumberLiteral;
    }

    private static FormulaType thresholdType(Node node) {
        if (node instanceof Node.StringLiteral) {
            return FormulaType.STRING;
        }
        if (node instanceof Node.BoolLiteral) {
            return FormulaType.BOOLEAN;
        }
        if (node instanceof Node.DateLiteral) {
            return FormulaType.DATE;
        }
        return FormulaType.NUMBER;
    }

    private static String displayValue(Node node) {
        if (node instanceof Node.NumberLiteral n) {
            return n.raw() + (n.wasPercent() ? "%" : "");
        }
        if (node instanceof Node.Unary u && u.operand() instanceof Node.NumberLiteral n) {
            return "-" + n.raw() + (n.wasPercent() ? "%" : "");
        }
        if (node instanceof Node.StringLiteral s) {
            return s.value();
        }
        if (node instanceof Node.BoolLiteral b) {
            return b.value() ? "TRUE" : "FALSE";
        }
        if (node instanceof Node.DateLiteral d) {
            return d.value().toString();
        }
        return "";
    }

    private static String formatEdit(FormulaThresholdEdit edit) {
        String v = edit.value() == null ? "" : edit.value().trim();
        return switch (edit.valueType()) {
            case STRING -> "'" + v.replace("'", "''") + "'";
            case BOOLEAN -> "TRUE".equalsIgnoreCase(v) ? "TRUE" : "FALSE";
            case DATE -> "DATE('" + v + "')";
            case NUMBER -> v;
        };
    }
}
