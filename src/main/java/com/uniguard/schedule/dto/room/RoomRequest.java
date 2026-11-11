package com.uniguard.schedule.dto.room;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.uniguard.schedule.entity.enums.RoomType;
import com.uniguard.schedule.entity.enums.HallGender;

public record RoomRequest(
	@NotBlank(message = "Room name is required")
	@Size(max = 120, message = "Room name must be at most 120 characters")
	String name,

	@Min(value = 1, message = "Capacity must be at least 1")
	int capacity,

	@NotNull(message = "Room type is required")
	RoomType type,

	@Min(value = 0, message = "Minimum invigilators cannot be negative")
	int minInvigilators,

	/** Optional, defaults to MIXED (anyone may supervise). */
	HallGender hallGender,

	/** Optional room type (Rooms page → Room types). Empty: guessed from the name for a new room, unchanged on edit. */
	java.util.UUID roomCategoryId,

	/** Optional. false = out of service. Empty: in service for a new room, unchanged on edit. */
	Boolean active
) {
	/** The four-field form used before labs and out-of-service existed. */
	public RoomRequest(String name, int capacity, RoomType type, int minInvigilators, HallGender hallGender) {
		this(name, capacity, type, minInvigilators, hallGender, null, null);
	}
}