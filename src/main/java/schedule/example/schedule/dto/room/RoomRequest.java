package schedule.example.schedule.dto.room;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import schedule.example.schedule.entity.enums.RoomType;

public record RoomRequest(
	@NotBlank(message = "Room name is required")
	@Size(max = 120, message = "Room name must be at most 120 characters")
	String name,

	@Min(value = 1, message = "Capacity must be at least 1")
	int capacity,

	@NotNull(message = "Room type is required")
	RoomType type,

	@Min(value = 0, message = "Minimum invigilators cannot be negative")
	int minInvigilators
) {
}