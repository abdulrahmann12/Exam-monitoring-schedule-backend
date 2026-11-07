package com.uniguard.schedule.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The signed-in person changes their own name (shown in the app; the email never changes here). */
public record UpdateProfileRequest(
	@NotBlank(message = "Name is required")
	@Size(max = 120, message = "Name must be at most 120 characters")
	String displayName
) {
}
