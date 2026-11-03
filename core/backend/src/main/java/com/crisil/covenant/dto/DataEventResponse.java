package com.crisil.covenant.dto;

/** C5 result: how many assessments were run, and whether the event had already been received. */
public record DataEventResponse(int assessmentsQueued, boolean duplicate) {
}
