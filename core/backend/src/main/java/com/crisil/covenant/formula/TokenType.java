package com.crisil.covenant.formula;

/** Lexical token kinds produced by the {@link Lexer}. */
enum TokenType {
    NUMBER,
    STRING,
    IDENT,
    KW_AND,
    KW_OR,
    KW_NOT,
    KW_TRUE,
    KW_FALSE,
    KW_DATE,
    LPAREN,
    RPAREN,
    COMMA,
    PERCENT,
    PLUS,
    MINUS,
    STAR,
    SLASH,
    GT,
    LT,
    GE,
    LE,
    EQ,
    NE,
    EOF
}
