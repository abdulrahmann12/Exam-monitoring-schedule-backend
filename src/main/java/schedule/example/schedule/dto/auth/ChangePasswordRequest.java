package schedule.example.schedule.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
	@NotBlank(message = "Current password is required")
	String currentPassword,

	@NotBlank(message = "New password is required")
	@Size(min = 10, max = 128, message = "New password must be between 10 and 128 characters")
	String newPassword
) {
}
