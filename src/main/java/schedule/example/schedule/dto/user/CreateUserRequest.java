package schedule.example.schedule.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import schedule.example.schedule.entity.enums.AdminRole;

/**
 * New user. Either {@code sendInvite=true} (the user gets an email link and chooses their own
 * password) or a {@code temporaryPassword} the user must replace at first sign-in.
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

	String temporaryPassword,

	Boolean sendInvite
) {

	public boolean invite() {
		return Boolean.TRUE.equals(sendInvite);
	}
}
