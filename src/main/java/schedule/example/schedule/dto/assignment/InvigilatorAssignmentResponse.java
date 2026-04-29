package schedule.example.schedule.dto.assignment;

import java.time.Instant;
import java.util.UUID;

public record InvigilatorAssignmentResponse(
	UUID id,
	UUID invigilatorId,
	String invigilatorName,
	int positionIndex,
	boolean required,
	Instant createdAt
) {
}