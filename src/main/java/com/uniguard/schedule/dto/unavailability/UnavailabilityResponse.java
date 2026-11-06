package com.uniguard.schedule.dto.unavailability;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record UnavailabilityResponse(
	UUID id,
	UUID personId,
	LocalDate startDate,
	LocalDate endDate,
	String reason,
	Instant createdAt
) {
}
