package schedule.example.schedule.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import schedule.example.schedule.entity.enums.AdminRole;

/**
 * New user with a temporary password chosen by the administrator. The user must replace it at
 * first sign-in (email invitations come in a later step).
 */
public record CreateUserRequest(
	@NotBlank(message = "Email is required")
	@Email(message = "Email is not valid")
	@Size(max = 160, message = "Email must be at most 160 characters")
	String email,

	@Size(max = 120, message = "Name must be at most 120 characters")
	String displayName,

	@NotNull(message = "Role is required")
	AdminRole role,

	@NotBlank(message = "Temporary password is required")
	String temporaryPassword
) {
}
