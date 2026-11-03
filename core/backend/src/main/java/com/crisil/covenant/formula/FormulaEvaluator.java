package com.crisil.covenant.formula;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * Tree-walking evaluator. Numeric values are {@link Double}; measure/aggregation leaves are looked
 * up in the supplied value map by their canonical {@link FormulaKeys key}. Runtime problems
 * (missing value, division by zero, unexpected operand type) raise {@link FormulaException}; the
 * type checker makes the last of these unreachable for well-typed formulas.
 */
final class FormulaEvaluator {

    Object eval(Node node, Map<String, Object> values) {
        if (node instanceof Node.NumberLiteral n) {
            return n.value();
        } else if (node instanceof Node.StringLiteral s) {
            return s.value();
        } else if (node instanceof Node.DateLiteral d) {
            return d.value();
        } else if (node instanceof Node.BoolLiteral b) {
            return b.value();
        } else if (node instanceof Node.MeasureRef m) {
            return required(values, FormulaKeys.key(m), "measure '" + m.name() + "'");
        } else if (node instanceof Node.Aggregation a) {
            return required(values, FormulaKeys.key(a),
                    a.function() + "(" + a.measure().name() + ", '" + a.window().canonical() + "')");
        } else if (node instanceof Node.Unary u) {
            return unary(u, values);
        } else {
            return binary((Node.Binary) node, values);
        }
    }

    private Object unary(Node.Unary u, Map<String, Object> values) {
        if (u.op() == UnaryOp.NEGATE) {
            return -asDouble(eval(u.operand(), values));
        }
        return !asBoolean(eval(u.operand(), values));
    }

    private Object binary(Node.Binary b, Map<String, Object> values) {
        switch (b.op().category()) {
            case ARITHMETIC -> {
                return arithmetic(b.op(), asDouble(eval(b.left(), values)), asDouble(eval(b.right(), values)));
            }
            case COMPARISON -> {
                return compare(b.op(), eval(b.left(), values), eval(b.right(), values));
            }
            case EQUALITY -> {
                boolean equal = Objects.equals(eval(b.left(), values), eval(b.right(), values));
                return b.op() == BinaryOperator.EQ ? equal : !equal;
            }
            default -> {
                boolean left = asBoolean(eval(b.left(), values));
                if (b.op() == BinaryOperator.AND && !left) {
                    return Boolean.FALSE;
                }
                if (b.op() == BinaryOperator.OR && left) {
                    return Boolean.TRUE;
                }
                return asBoolean(eval(b.right(), values));
            }
        }
    }

    private static double arithmetic(BinaryOperator op, double l, double r) {
        if (op == BinaryOperator.ADD) {
            return l + r;
        }
        if (op == BinaryOperator.SUB) {
            return l - r;
        }
        if (op == BinaryOperator.MUL) {
            return l * r;
        }
        if (r == 0.0) {
            throw new FormulaException("Division by zero");
        }
        return l / r;
    }

    private static boolean compare(BinaryOperator op, Object lv, Object rv) {
        int cmp;
        if (lv instanceof Number ln && rv instanceof Number rn) {
            cmp = Double.compare(ln.doubleValue(), rn.doubleValue());
        } else if (lv instanceof LocalDate ld && rv instanceof LocalDate rd) {
            cmp = ld.compareTo(rd);
        } else {
            throw new FormulaException("Cannot compare " + describe(lv) + " with " + describe(rv));
        }
        if (op == BinaryOperator.GT) {
            return cmp > 0;
        }
        if (op == BinaryOperator.LT) {
            return cmp < 0;
        }
        if (op == BinaryOperator.GE) {
            return cmp >= 0;
        }
        return cmp <= 0;
    }

    private static Object required(Map<String, Object> values, String key, String label) {
        Object value = values.get(key);
        if (value == null) {
            throw new FormulaException("No value available for " + label);
        }
        return value;
    }

    private static double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new FormulaException("Expected a number but got " + describe(value));
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw new FormulaException("Expected true/false but got " + describe(value));
    }

    private static String describe(Object value) {
        // Operands always reach here non-null: leaves come from required() (non-null) and
        // intermediate results are non-null Double/Boolean/LocalDate/String.
        return value.getClass().getSimpleName();
    }
}
