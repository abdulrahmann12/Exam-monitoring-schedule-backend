package schedule.example.schedule.dto.timeslot;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

public record TimeSlotResponse(
        UUID id,
        UUID scheduleGroupId,
        String label,
        LocalTime startTime,
        LocalTime endTime,
        int sortOrder,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}