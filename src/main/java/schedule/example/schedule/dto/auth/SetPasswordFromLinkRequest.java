package schedule.example.schedule.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetPasswordFromLinkRequest(
	@NotBlank(message = "The link is incomplete")
	@Size(max = 200, message = "The link is not valid")
	String token,

	@NotBlank(message = "New password is required")
	String newPassword
) {
}
