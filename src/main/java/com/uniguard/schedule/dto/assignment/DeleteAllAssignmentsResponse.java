package com.uniguard.schedule.dto.assignment;

/** How many exam rooms were removed from one exam period. */
public record DeleteAllAssignmentsResponse(int deleted, String examPeriod) {
}
