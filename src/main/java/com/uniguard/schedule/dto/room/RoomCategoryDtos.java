package com.uniguard.schedule.dto.room;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Room types (Rooms page → Room types). */
public final class RoomCategoryDtos {

	private RoomCategoryDtos() {
	}

	/** @param special true = only for exams that ask for this type (labs, studios) */
	public record RoomCategoryRequest(
		@NotBlank(message = "Room type name is required")
		@Size(max = 60, message = "Room type name must be at most 60 characters")
		String name,
		boolean special
	) {
	}

	/** @param rooms how many rooms have this type */
	public record RoomCategoryResponse(UUID id, String name, boolean special, int sortOrder, long rooms) {
	}
}
