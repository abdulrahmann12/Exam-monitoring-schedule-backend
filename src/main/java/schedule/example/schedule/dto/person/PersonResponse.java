package schedule.example.schedule.dto.person;

import schedule.example.schedule.entity.enums.PersonRole;
import schedule.example.schedule.entity.enums.WeekDay;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record PersonResponse(
	UUID id,
	String name,
	String department,
	String email,
	PersonRole role,
	Set<WeekDay> availableDays,
	int totalAssignments,
	boolean active,
	int maxParallelRooms,
	Instant createdAt,
	Instant updatedAt
) {
	public PersonResponse withTotalAssignments(int nextTotalAssignments) {
		return new PersonResponse(
			id,
			name,
			department,
			email,
			role,
			availableDays,
			nextTotalAssignments,
			active,
			maxParallelRooms,
			createdAt,
			updatedAt
		);
	}
}