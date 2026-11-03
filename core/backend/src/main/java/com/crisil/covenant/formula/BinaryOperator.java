package com.crisil.covenant.formula;

/** Binary operators grouped by the type rule that governs them. */
enum BinaryOperator {
    ADD("+", Category.ARITHMETIC),
    SUB("-", Category.ARITHMETIC),
    MUL("*", Category.ARITHMETIC),
    DIV("/", Category.ARITHMETIC),
    GT(">", Category.COMPARISON),
    LT("<", Category.COMPARISON),
    GE(">=", Category.COMPARISON),
    LE("<=", Category.COMPARISON),
    EQ("==", Category.EQUALITY),
    NE("!=", Category.EQUALITY),
    AND("AND", Category.LOGICAL),
    OR("OR", Category.LOGICAL);

    enum Category {
        ARITHMETIC,
        COMPARISON,
        EQUALITY,
        LOGICAL
    }

    private final String symbol;
    private final Category category;

    BinaryOperator(String symbol, Category category) {
        this.symbol = symbol;
        this.category = category;
    }

    String symbol() {
        return symbol;
    }

    Category category() {
        return category;
    }
}
