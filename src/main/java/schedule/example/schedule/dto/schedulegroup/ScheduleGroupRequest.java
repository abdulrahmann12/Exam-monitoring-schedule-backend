package schedule.example.schedule.dto.schedulegroup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ScheduleGroupRequest(
	@NotBlank(message = "Schedule group name is required")
	@Size(max = 120, message = "Schedule group name must be at most 120 characters")
	String name,

	@Size(max = 500, message = "Description must be at most 500 characters")
	String description,

	Boolean active
) {
}
