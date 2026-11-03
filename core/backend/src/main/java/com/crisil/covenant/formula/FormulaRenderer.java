package com.crisil.covenant.formula;

import java.time.LocalDate;
import java.util.Map;

/**
 * Renders a formula with its measure/aggregation leaves replaced by resolved values, for the
 * audit trail and the authoring "sample evaluation" panel (e.g. {@code 2.0 > 1.2}). Binary
 * sub-expressions are parenthesised so the rendered text is unambiguous.
 */
final class FormulaRenderer {

    String render(Node node, Map<String, Object> values) {
        if (node instanceof Node.NumberLiteral n) {
            return n.raw() + (n.wasPercent() ? "%" : "");
        } else if (node instanceof Node.StringLiteral s) {
            return "'" + s.value() + "'";
        } else if (node instanceof Node.DateLiteral d) {
            return "DATE('" + d.value() + "')";
        } else if (node instanceof Node.BoolLiteral b) {
            return b.value() ? "TRUE" : "FALSE";
        } else if (node instanceof Node.MeasureRef m) {
            return format(values.get(FormulaKeys.key(m)));
        } else if (node instanceof Node.Aggregation a) {
            return format(values.get(FormulaKeys.key(a)));
        } else if (node instanceof Node.Unary u) {
            return u.op() == UnaryOp.NEGATE
                    ? "-" + operand(u.operand(), values)
                    : "NOT " + operand(u.operand(), values);
        } else {
            Node.Binary b = (Node.Binary) node;
            return operand(b.left(), values) + " " + b.op().symbol() + " " + operand(b.right(), values);
        }
    }

    private String operand(Node node, Map<String, Object> values) {
        String rendered = render(node, values);
        return node instanceof Node.Binary ? "(" + rendered + ")" : rendered;
    }

    static String format(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "'" + s + "'";
        }
        if (value instanceof LocalDate d) {
            return d.toString();
        }
        return value.toString();
    }
}
