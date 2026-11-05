package schedule.example.schedule.dto.room;

import schedule.example.schedule.entity.enums.RoomType;
import schedule.example.schedule.entity.enums.HallGender;

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