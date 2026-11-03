package com.crisil.covenant.dto;

/** C2: what one reminder run did. */
public record ReminderRunResult(int contacts, int sent, int failed) {
}
