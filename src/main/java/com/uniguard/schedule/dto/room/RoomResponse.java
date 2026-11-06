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
	Instant createdAt,
	Instant updatedAt
) {
}