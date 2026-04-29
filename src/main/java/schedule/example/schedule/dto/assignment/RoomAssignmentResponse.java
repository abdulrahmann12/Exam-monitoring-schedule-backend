package schedule.example.schedule.dto.assignment;

import schedule.example.schedule.entity.enums.AssignmentSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record RoomAssignmentResponse(
	UUID id,
	LocalDate examDate,
	UUID roomId,
	String roomName,
	UUID timeSlotId,
	String slotLabel,
	LocalTime startTime,
	LocalTime endTime,
	String subjectName,
	String subjectCode,
	UUID chiefInvigilatorId,
	String chiefInvigilatorName,
	boolean locked,
	int generationVersion,
	AssignmentSource source,
	List<InvigilatorAssignmentResponse> invigilators,
	Instant createdAt,
	Instant updatedAt
) {
}