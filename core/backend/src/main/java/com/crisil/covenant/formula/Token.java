package com.crisil.covenant.formula;

/**
 * A lexical token. {@code text} holds the lexeme for NUMBER/IDENT and the already-unescaped inner
 * content for STRING. {@code [start, end)} are 0-based source offsets.
 */
record Token(TokenType type, String text, int start, int end) {
}
