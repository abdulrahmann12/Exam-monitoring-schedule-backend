package schedule.example.schedule.dto.assignment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** One room in a draft (unsaved) slot schedule — sent to and returned from the generate preview. */
public record PreviewAssignment(
	@NotNull(message = "Room id is required")
	UUID roomId,

	UUID chiefInvigilatorId,

	@NotNull(message = "Invigilator ids list is required")
	List<UUID> invigilatorIds,

	boolean locked,

	@Size(max = 160, message = "Subject name must be at most 160 characters")
	String subjectName,

	@Size(max = 40, message = "Subject code must be at most 40 characters")
	String subjectCode
) {
}
