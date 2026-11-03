package com.crisil.covenant.dto;

public record TestConnectionResult(boolean reachable, int columnCount, String message) {

    public static TestConnectionResult ok(int columnCount) {
        return new TestConnectionResult(true, columnCount, "Connected — discovered " + columnCount + " column(s)");
    }

    public static TestConnectionResult failed(String message) {
        return new TestConnectionResult(false, 0, message);
    }
}
