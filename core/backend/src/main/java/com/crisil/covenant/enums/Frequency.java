package com.crisil.covenant.enums;

public enum Frequency {
    DAILY("0 0 2 * * ?"),
    WEEKLY("0 0 2 ? * MON"),
    MONTHLY("0 0 2 1 * ?"),
    QUARTERLY("0 0 2 1 1,4,7,10 ?"),
    YEARLY("0 0 2 1 1 ?"),
    CUSTOM(null);

    private final String defaultCron;

    Frequency(String defaultCron) {
        this.defaultCron = defaultCron;
    }

    public String defaultCron() {
        return defaultCron;
    }
}
