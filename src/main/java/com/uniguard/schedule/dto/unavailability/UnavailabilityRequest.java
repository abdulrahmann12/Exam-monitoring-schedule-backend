package com.uniguard.schedule.dto.unavailability;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UnavailabilityRequest(
	@NotNull(message = "Start date is required")
	LocalDate startDate,

	@NotNull(message = "End date is required")
	LocalDate endDate,

	@Size(max = 200, message = "Reason must be at most 200 characters")
	String reason
) {
}
