package com.crisil.covenant.formula;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Recursive-descent parser producing the {@link Node} AST. Operator precedence (loosest to
 * tightest): OR, AND, NOT, comparison (non-associative), additive, multiplicative, unary minus,
 * primary. Throws {@link FormulaSyntaxException} on the first structural error, carrying the
 * offending source offsets so the editor can underline it.
 */
final class Parser {

    private static final int MAX_DEPTH = 32;

    private final List<Token> tokens;
    private int index;
    private int depth;

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    Node parse() {
        Node node = parseOr();
        expect(TokenType.EOF, "Expected end of formula");
        return node;
    }

    private Node parseOr() {
        Node left = parseAnd();
        while (check(TokenType.KW_OR)) {
            advance();
            Node right = parseAnd();
            left = new Node.Binary(BinaryOperator.OR, left, right, left.start(), right.end());
        }
        return left;
    }

    private Node parseAnd() {
        Node left = parseNot();
        while (check(TokenType.KW_AND)) {
            advance();
            Node right = parseNot();
            left = new Node.Binary(BinaryOperator.AND, left, right, left.start(), right.end());
        }
        return left;
    }

    private Node parseNot() {
        if (check(TokenType.KW_NOT)) {
            Token not = advance();
            enter();
            Node operand = parseNot();
            leave();
            return new Node.Unary(UnaryOp.NOT, operand, not.start(), operand.end());
        }
        return parseComparison();
    }

    private Node parseComparison() {
        Node left = parseAdditive();
        BinaryOperator op = comparisonOp(peek().type());
        if (op == null) {
            return left;
        }
        advance();
        Node right = parseAdditive();
        Node result = new Node.Binary(op, left, right, left.start(), right.end());
        if (comparisonOp(peek().type()) != null) {
            Token bad = peek();
            throw err(ErrorCode.CHAINED_COMPARISON,
                    "Chained comparison is not allowed; combine with AND instead", bad);
        }
        return result;
    }

    private static BinaryOperator comparisonOp(TokenType type) {
        return switch (type) {
            case GT -> BinaryOperator.GT;
            case LT -> BinaryOperator.LT;
            case GE -> BinaryOperator.GE;
            case LE -> BinaryOperator.LE;
            case EQ -> BinaryOperator.EQ;
            case NE -> BinaryOperator.NE;
            default -> null;
        };
    }

    private Node parseAdditive() {
        Node left = parseMultiplicative();
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            BinaryOperator op = advance().type() == TokenType.PLUS ? BinaryOperator.ADD : BinaryOperator.SUB;
            Node right = parseMultiplicative();
            left = new Node.Binary(op, left, right, left.start(), right.end());
        }
        return left;
    }

    private Node parseMultiplicative() {
        Node left = parseUnary();
        while (check(TokenType.STAR) || check(TokenType.SLASH)) {
            BinaryOperator op = advance().type() == TokenType.STAR ? BinaryOperator.MUL : BinaryOperator.DIV;
            Node right = parseUnary();
            left = new Node.Binary(op, left, right, left.start(), right.end());
        }
        return left;
    }

    private Node parseUnary() {
        if (check(TokenType.MINUS)) {
            Token minus = advance();
            enter();
            Node operand = parseUnary();
            leave();
            return new Node.Unary(UnaryOp.NEGATE, operand, minus.start(), operand.end());
        }
        return parsePrimary();
    }

    private Node parsePrimary() {
        Token t = peek();
        switch (t.type()) {
            case LPAREN -> {
                advance();
                enter();
                Node inner = parseOr();
                leave();
                expect(TokenType.RPAREN, "Expected ')'");
                return inner;
            }
            case NUMBER -> {
                advance();
                if (check(TokenType.PERCENT)) {
                    Token pct = advance();
                    return new Node.NumberLiteral(
                            parseDouble(t) / 100.0, true, t.text(), t.start(), pct.end());
                }
                return new Node.NumberLiteral(parseDouble(t), false, t.text(), t.start(), t.end());
            }
            case STRING -> {
                advance();
                return new Node.StringLiteral(t.text(), t.start(), t.end());
            }
            case KW_TRUE -> {
                advance();
                return new Node.BoolLiteral(true, t.start(), t.end());
            }
            case KW_FALSE -> {
                advance();
                return new Node.BoolLiteral(false, t.start(), t.end());
            }
            case KW_DATE -> {
                return dateLiteral();
            }
            case IDENT -> {
                advance();
                if (check(TokenType.LPAREN)) {
                    return functionCall(t);
                }
                return new Node.MeasureRef(t.text(), t.start(), t.end());
            }
            default -> throw err(ErrorCode.PARSE_ERROR,
                    "Expected a value, measure, or '('", t);
        }
    }

    private Node dateLiteral() {
        Token dateKw = advance();
        expect(TokenType.LPAREN, "Expected '(' after DATE");
        Token str = peek();
        if (str.type() != TokenType.STRING) {
            throw err(ErrorCode.PARSE_ERROR,
                    "Expected a date string like '2026-03-31' inside DATE(...)", str);
        }
        advance();
        LocalDate value;
        try {
            value = LocalDate.parse(str.text());
        } catch (DateTimeParseException e) {
            throw err(ErrorCode.PARSE_ERROR,
                    "Invalid date '" + str.text() + "' (expected YYYY-MM-DD)", str);
        }
        Token close = expect(TokenType.RPAREN, "Expected ')' to close DATE(...)");
        return new Node.DateLiteral(value, dateKw.start(), close.end());
    }

    private Node functionCall(Token name) {
        Optional<FunctionKind> kind = FunctionKind.fromName(name.text());
        if (kind.isEmpty()) {
            throw err(ErrorCode.UNKNOWN_FUNCTION,
                    "Unknown function '" + name.text()
                            + "' (allowed: AVG, SUM, MIN, MAX, LATEST, CURRENT, COUNT)", name);
        }
        expect(TokenType.LPAREN, "Expected '('");
        Token arg = peek();
        if (arg.type() != TokenType.IDENT) {
            throw err(ErrorCode.ARG_TYPE,
                    name.text() + " expects a measure as its first argument", arg);
        }
        advance();
        Node.MeasureRef measure = new Node.MeasureRef(arg.text(), arg.start(), arg.end());
        if (check(TokenType.RPAREN)) {
            throw err(ErrorCode.BAD_ARITY,
                    name.text() + " expects (measure, window), got 1 argument", peek());
        }
        expect(TokenType.COMMA, "Expected ',' between measure and window");
        Token win = peek();
        if (win.type() != TokenType.STRING) {
            throw err(ErrorCode.INVALID_WINDOW,
                    "Expected a window such as '2y' as the second argument", win);
        }
        advance();
        Optional<Window> window = Window.parse(win.text());
        if (window.isEmpty()) {
            throw err(ErrorCode.INVALID_WINDOW,
                    "Invalid window '" + win.text()
                            + "' (use e.g. 2y, 6m, 90d, 1q, 4w; amount 1-1000)", win);
        }
        SamplingKeyword sampling = SamplingKeyword.FLAT;
        if (check(TokenType.COMMA)) {
            advance();
            sampling = samplingArgument(name);
        }
        if (check(TokenType.COMMA)) {
            throw err(ErrorCode.BAD_ARITY,
                    name.text() + " expects (measure, window[, sampling])", peek());
        }
        Token close = expect(TokenType.RPAREN, "Expected ')' to close " + name.text());
        return new Node.Aggregation(kind.get(), measure, window.get(),
                sampling.mode(), sampling.inclusion(), name.start(), close.end());
    }

    /**
     * Parse the optional third argument of an aggregation: a bare sampling keyword such as
     * {@code PERIODIC} (unquoted, unlike the string window). An unknown or non-identifier token is an
     * {@link ErrorCode#INVALID_SAMPLING} error.
     */
    private SamplingKeyword samplingArgument(Token name) {
        Token arg = peek();
        Optional<SamplingKeyword> keyword = arg.type() == TokenType.IDENT
                ? SamplingKeyword.fromName(arg.text())
                : Optional.empty();
        if (keyword.isEmpty()) {
            throw err(ErrorCode.INVALID_SAMPLING,
                    name.text() + " sampling must be one of FLAT, PERIODIC, PERIODIC_CURRENT, "
                            + "PERIODIC_COMPLETED", arg);
        }
        advance();
        return keyword.get();
    }

    private double parseDouble(Token t) {
        try {
            return Double.parseDouble(t.text());
        } catch (NumberFormatException e) {
            throw err(ErrorCode.PARSE_ERROR, "Invalid number '" + t.text() + "'", t);
        }
    }

    // -- helpers ---------------------------------------------------------

    private Token peek() {
        return tokens.get(index);
    }

    private Token advance() {
        return tokens.get(index++);
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private Token expect(TokenType type, String message) {
        if (check(type)) {
            return advance();
        }
        throw err(ErrorCode.PARSE_ERROR, message + " but found " + describe(peek()), peek());
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            Token t = peek();
            throw new FormulaSyntaxException(ErrorCode.TOO_DEEP,
                    "Formula is nested too deeply (max " + MAX_DEPTH + ")", t.start(), t.end());
        }
    }

    private void leave() {
        depth--;
    }

    private static String describe(Token t) {
        return t.type() == TokenType.EOF ? "end of formula" : "'" + t.text() + "'";
    }

    private static FormulaSyntaxException err(ErrorCode code, String message, Token at) {
        int start = at.start();
        int end = Math.max(at.end(), start + 1);
        return new FormulaSyntaxException(code, message, start, end);
    }
}
