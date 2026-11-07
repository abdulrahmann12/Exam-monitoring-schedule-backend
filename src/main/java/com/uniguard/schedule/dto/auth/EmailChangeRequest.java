package com.uniguard.schedule.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The signed-in person asks to sign in with another email; the current password proves it is them. */
public record EmailChangeRequest(
	@NotBlank(message = "New email is required")
	@Email(message = "Email is not valid")
	@Size(max = 160, message = "Email must be at most 160 characters")
	String newEmail,

	@NotBlank(message = "Current password is required")
	@Size(max = 128, message = "Password is too long")
	String currentPassword
) {
}
