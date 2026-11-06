package com.uniguard.schedule.dto.user;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.uniguard.schedule.entity.enums.AdminRole;

/** Changes name, role and enabled flag. Email and password have their own flows. */
public record UpdateUserRequest(
	@Size(max = 120, message = "Name must be at most 120 characters")
	String displayName,

	@NotNull(message = "Role is required")
	AdminRole role,

	@NotNull(message = "Enabled is required")
	Boolean enabled
) {
}
