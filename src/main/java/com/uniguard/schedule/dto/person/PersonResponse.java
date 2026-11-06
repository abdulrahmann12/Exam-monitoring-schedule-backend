package com.uniguard.schedule.dto.person;

import com.uniguard.schedule.entity.enums.PersonRole;
import com.uniguard.schedule.entity.enums.Gender;
import com.uniguard.schedule.entity.enums.WeekDay;

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
	Gender gender,
	Set<String> subjectCodes,
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
			gender,
			subjectCodes,
			createdAt,
			updatedAt
		);
	}
}