package com.crisil.covenant.dto;

/**
 * B6: one thing waiting on the signed-in user, for the bell in the top bar.
 *
 * @param kind     APPROVAL (a Waiver/Extension for you to decide), BREACH (broken, nothing decided yet),
 *                 OVERDUE (a check is late), AT_RISK (compliant but close to its limit),
 *                 REVIEW (a person-checked covenant to record)
 * @param cpId     the CP it is about, so the screen can link to it
 * @param message  a short sentence for the list
 */
public record AlertView(String kind, String cpId, String appliedCovenantId, String covenantName, String message) {
}
