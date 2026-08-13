package schedule.example.schedule.dto.assignment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import schedule.example.schedule.entity.enums.AssignmentSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RoomAssignmentRequest(
		UUID scheduleGroupId,

		@NotNull(message = "Exam date is required")
		LocalDate examDate,

		@NotNull(message = "Room id is required")
		UUID roomId,

		@NotNull(message = "Time slot id is required")
		UUID timeSlotId,

		@Size(max = 160, message = "Subject name must be at most 160 characters")
		String subjectName,

		@Size(max = 40, message = "Subject code must be at most 40 characters")
		String subjectCode,

		UUID chiefInvigilatorId,

		@NotNull(message = "Locked state is required")
		Boolean locked,

		AssignmentSource source,

		@NotNull(message = "Invigilator ids list is required")
		List<UUID> invigilatorIds
) {
}