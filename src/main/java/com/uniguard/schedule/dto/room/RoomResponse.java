package com.uniguard.schedule.dto.room;

import com.uniguard.schedule.entity.enums.RoomType;
import com.uniguard.schedule.entity.enums.HallGender;

import java.time.Instant;
import java.util.UUID;

public record RoomResponse(
	UUID id,
	String name,
	int capacity,
	RoomType type,
	int minInvigilators,
	HallGender hallGender,
	boolean active,
	/** Room type; null = none (a normal room). */
	UUID roomCategoryId,
	String roomCategoryName,
	/** true = special type (lab, studio): only for exams that ask for it. */
	boolean roomCategorySpecial,
	/** Building, or null when not known. */
	String building,
	Instant createdAt,
	Instant updatedAt
) {
}