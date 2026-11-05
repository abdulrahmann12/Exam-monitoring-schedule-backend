package schedule.example.schedule.dto.user;

import jakarta.validation.constraints.NotBlank;

/** Administrator sets a new temporary password; the user must change it at next sign-in. */
public record ResetPasswordRequest(
	@NotBlank(message = "Temporary password is required")
	String temporaryPassword
) {
}
