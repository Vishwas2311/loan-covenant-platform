package com.crisil.covenant.formula;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts formula source into a flat list of {@link Token}s ending with EOF. A single forward
 * scan (no backtracking). On an illegal character or unterminated string it throws a
 * {@link FormulaSyntaxException} with {@link ErrorCode#LEX_ERROR} and the offending offset.
 */
final class Lexer {

    private final String src;
    private int pos;

    Lexer(String src) {
        this.src = src;
    }

    List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (Character.isDigit(c)) {
                tokens.add(number());
            } else if (c == '\'') {
                tokens.add(string());
            } else if (isIdentStart(c)) {
                tokens.add(identifier());
            } else {
                tokens.add(symbol());
            }
        }
        tokens.add(new Token(TokenType.EOF, "", pos, pos));
        return tokens;
    }

    private Token number() {
        int start = pos;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
            pos++;
        }
        if (pos < src.length() && src.charAt(pos) == '.'
                && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))) {
            pos++; // consume '.'
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
        }
        return new Token(TokenType.NUMBER, src.substring(start, pos), start, pos);
    }

    private Token string() {
        int start = pos;
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\'') {
                if (pos + 1 < src.length() && src.charAt(pos + 1) == '\'') {
                    sb.append('\''); // escaped quote
                    pos += 2;
                } else {
                    pos++; // closing quote
                    return new Token(TokenType.STRING, sb.toString(), start, pos);
                }
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new FormulaSyntaxException(
                ErrorCode.LEX_ERROR, "Unterminated string literal", start, src.length());
    }

    private Token identifier() {
        int start = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        String text = src.substring(start, pos);
        TokenType keyword = keyword(text);
        return new Token(keyword == null ? TokenType.IDENT : keyword, text, start, pos);
    }

    private static TokenType keyword(String text) {
        return switch (text.toLowerCase(java.util.Locale.ROOT)) {
            case "and" -> TokenType.KW_AND;
            case "or" -> TokenType.KW_OR;
            case "not" -> TokenType.KW_NOT;
            case "true" -> TokenType.KW_TRUE;
            case "false" -> TokenType.KW_FALSE;
            case "date" -> TokenType.KW_DATE;
            default -> null;
        };
    }

    private Token symbol() {
        int start = pos;
        char c = src.charAt(pos);
        switch (c) {
            case '(' -> { return single(TokenType.LPAREN, start); }
            case ')' -> { return single(TokenType.RPAREN, start); }
            case ',' -> { return single(TokenType.COMMA, start); }
            case '%' -> { return single(TokenType.PERCENT, start); }
            case '+' -> { return single(TokenType.PLUS, start); }
            case '-' -> { return single(TokenType.MINUS, start); }
            case '*' -> { return single(TokenType.STAR, start); }
            case '/' -> { return single(TokenType.SLASH, start); }
            case '>' -> { return relational(TokenType.GE, TokenType.GT, start); }
            case '<' -> { return relational(TokenType.LE, TokenType.LT, start); }
            case '=' -> { return requireEquals(TokenType.EQ, start, "Use '==' for equality"); }
            case '!' -> { return requireEquals(TokenType.NE, start, "Use '!=' for inequality"); }
            default -> throw new FormulaSyntaxException(
                    ErrorCode.LEX_ERROR, "Unexpected character '" + c + "'", start, start + 1);
        }
    }

    private Token single(TokenType type, int start) {
        pos++;
        return new Token(type, src.substring(start, pos), start, pos);
    }

    private Token relational(TokenType withEquals, TokenType bare, int start) {
        pos++;
        if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
            return new Token(withEquals, src.substring(start, pos), start, pos);
        }
        return new Token(bare, src.substring(start, pos), start, pos);
    }

    private Token requireEquals(TokenType type, int start, String hint) {
        if (pos + 1 < src.length() && src.charAt(pos + 1) == '=') {
            pos += 2;
            return new Token(type, src.substring(start, pos), start, pos);
        }
        throw new FormulaSyntaxException(
                ErrorCode.LEX_ERROR, hint, start, start + 1);
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
