package com.uniguard.schedule.dto.timeslot;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.UUID;

/** Time slots are shared by every exam period (an old client's "scheduleGroupId" is ignored). */
public record TimeSlotRequest(
        @Size(max = 120, message = "Label must be at most 120 characters")
        String label,

        @NotNull(message = "Start time is required")
        LocalTime startTime,

        @NotNull(message = "End time is required")
        LocalTime endTime,

        int sortOrder
) {
}