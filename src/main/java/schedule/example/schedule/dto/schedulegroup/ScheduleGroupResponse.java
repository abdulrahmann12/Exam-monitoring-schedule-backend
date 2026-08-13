package schedule.example.schedule.dto.schedulegroup;

import java.time.Instant;
import java.util.UUID;

public record ScheduleGroupResponse(
	UUID id,
	String name,
	String description,
	boolean active,
	long timeSlotCount,
	long assignmentCount,
	Instant createdAt,
	Instant updatedAt
) {
}
