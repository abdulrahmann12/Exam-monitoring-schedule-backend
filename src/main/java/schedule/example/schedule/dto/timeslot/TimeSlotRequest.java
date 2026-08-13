package schedule.example.schedule.dto.timeslot;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.UUID;

public record TimeSlotRequest(
        UUID scheduleGroupId,

        @Size(max = 120, message = "Label must be at most 120 characters")
        String label,

        @NotNull(message = "Start time is required")
        LocalTime startTime,

        @NotNull(message = "End time is required")
        LocalTime endTime,

        int sortOrder
) {
}