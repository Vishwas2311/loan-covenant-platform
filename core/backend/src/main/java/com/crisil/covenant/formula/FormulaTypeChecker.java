package com.crisil.covenant.formula;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Infers the {@link FormulaType} of each node and collects every semantic error in one pass
 * (so the editor can show all problems at once). On a sub-error it substitutes a fallback type
 * to keep checking without cascading spurious errors.
 */
final class FormulaTypeChecker {

    FormulaType check(Node node, MeasureCatalog catalog, List<FormulaError> errors) {
        if (node instanceof Node.NumberLiteral) {
            return FormulaType.NUMBER;
        } else if (node instanceof Node.StringLiteral) {
            return FormulaType.STRING;
        } else if (node instanceof Node.DateLiteral) {
            return FormulaType.DATE;
        } else if (node instanceof Node.BoolLiteral) {
            return FormulaType.BOOLEAN;
        } else if (node instanceof Node.MeasureRef m) {
            return measureType(m, catalog, errors);
        } else if (node instanceof Node.Aggregation a) {
            return aggregationType(a, catalog, errors);
        } else if (node instanceof Node.Unary u) {
            return unaryType(u, catalog, errors);
        } else {
            return binaryType((Node.Binary) node, catalog, errors);
        }
    }

    private FormulaType measureType(Node.MeasureRef m, MeasureCatalog catalog, List<FormulaError> errors) {
        Optional<MeasureMeta> meta = catalog.lookup(m.name());
        if (meta.isEmpty()) {
            errors.add(new FormulaError(ErrorCode.UNKNOWN_MEASURE,
                    unknownMeasureMessage(m.name(), catalog), m.start(), m.end()));
            return FormulaType.NUMBER;
        }
        return meta.get().type();
    }

    private FormulaType aggregationType(Node.Aggregation a, MeasureCatalog catalog, List<FormulaError> errors) {
        Optional<MeasureMeta> meta = catalog.lookup(a.measure().name());
        if (meta.isEmpty()) {
            errors.add(new FormulaError(ErrorCode.UNKNOWN_MEASURE,
                    unknownMeasureMessage(a.measure().name(), catalog),
                    a.measure().start(), a.measure().end()));
            return a.function().resultType(FormulaType.NUMBER);
        }
        FormulaType innerType = meta.get().type();
        if (a.function().requiresNumericInner() && innerType != FormulaType.NUMBER) {
            errors.add(new FormulaError(ErrorCode.ARG_TYPE,
                    a.function() + " requires a numeric measure; '" + a.measure().name()
                            + "' is " + innerType, a.start(), a.end()));
        }
        checkSampling(a, errors);
        return a.function().resultType(innerType);
    }

    /**
     * US-AGG-11 AC7: PERIODIC sampling needs a month/quarter/year window. It is a no-op for
     * {@code LATEST}/{@code CURRENT} (accepted, ignored); on the true aggregates a missing window or a
     * day/week window is an {@link ErrorCode#INVALID_WINDOW} error. FLAT imposes no constraint.
     */
    private static void checkSampling(Node.Aggregation a, List<FormulaError> errors) {
        if (a.sampling() != SamplingMode.PERIODIC) {
            return;
        }
        if (a.function() == FunctionKind.LATEST || a.function() == FunctionKind.CURRENT) {
            return;
        }
        if (a.window() == null || !a.window().unit().isPeriodCapable()) {
            errors.add(new FormulaError(ErrorCode.INVALID_WINDOW,
                    "PERIODIC sampling needs a month/quarter/year window (e.g. 3q, 6m, 2y), not "
                            + (a.window() == null ? "a bare measure" : "'" + a.window().canonical() + "'"),
                    a.start(), a.end()));
        }
    }

    private FormulaType unaryType(Node.Unary u, MeasureCatalog catalog, List<FormulaError> errors) {
        FormulaType operand = check(u.operand(), catalog, errors);
        if (u.op() == UnaryOp.NEGATE) {
            if (operand != FormulaType.NUMBER) {
                errors.add(new FormulaError(ErrorCode.TYPE_MISMATCH_ARITH,
                        "Unary '-' requires a number but got " + operand, u.start(), u.end()));
            }
            return FormulaType.NUMBER;
        }
        if (operand != FormulaType.BOOLEAN) {
            errors.add(new FormulaError(ErrorCode.TYPE_MISMATCH_LOGICAL,
                    "NOT requires a true/false operand but got " + operand, u.start(), u.end()));
        }
        return FormulaType.BOOLEAN;
    }

    private FormulaType binaryType(Node.Binary b, MeasureCatalog catalog, List<FormulaError> errors) {
        FormulaType left = check(b.left(), catalog, errors);
        FormulaType right = check(b.right(), catalog, errors);
        String sym = b.op().symbol();
        switch (b.op().category()) {
            case ARITHMETIC -> {
                if (left != FormulaType.NUMBER || right != FormulaType.NUMBER) {
                    errors.add(new FormulaError(ErrorCode.TYPE_MISMATCH_ARITH,
                            "'" + sym + "' requires numeric operands", b.start(), b.end()));
                }
                return FormulaType.NUMBER;
            }
            case COMPARISON -> {
                boolean ok = (left == FormulaType.NUMBER && right == FormulaType.NUMBER)
                        || (left == FormulaType.DATE && right == FormulaType.DATE);
                if (!ok) {
                    errors.add(new FormulaError(ErrorCode.TYPE_MISMATCH_COMPARE,
                            "Cannot compare " + left + " with " + right + " using '" + sym + "'",
                            b.start(), b.end()));
                }
                return FormulaType.BOOLEAN;
            }
            case EQUALITY -> {
                if (left != right) {
                    errors.add(new FormulaError(ErrorCode.TYPE_MISMATCH_COMPARE,
                            "Cannot compare " + left + " with " + right + " using '" + sym + "'",
                            b.start(), b.end()));
                }
                return FormulaType.BOOLEAN;
            }
            default -> {
                if (left != FormulaType.BOOLEAN || right != FormulaType.BOOLEAN) {
                    errors.add(new FormulaError(ErrorCode.TYPE_MISMATCH_LOGICAL,
                            sym + " requires true/false operands (did you forget a comparison?)",
                            b.start(), b.end()));
                }
                return FormulaType.BOOLEAN;
            }
        }
    }

    private static String unknownMeasureMessage(String name, MeasureCatalog catalog) {
        Optional<String> suggestion = closest(name, catalog.names());
        return suggestion
                .map(s -> "Unknown measure '" + name + "'. Did you mean '" + s + "'?")
                .orElse("Unknown measure '" + name + "'");
    }

    private static Optional<String> closest(String target, Collection<String> names) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        String lowered = target.toLowerCase(java.util.Locale.ROOT);
        for (String candidate : names) {
            int distance = levenshtein(lowered, candidate.toLowerCase(java.util.Locale.ROOT));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best != null && bestDistance <= 2 ? Optional.of(best) : Optional.empty();
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }
}
