package com.uniguard.schedule.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequest(
	@NotBlank(message = "Email is required")
	@Email(message = "Email is not valid")
	@Size(max = 160, message = "Email must be at most 160 characters")
	String email
) {
}
