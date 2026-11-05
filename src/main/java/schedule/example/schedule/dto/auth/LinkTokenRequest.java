package schedule.example.schedule.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The secret part of an emailed link (taken from the page address after '#token='). */
public record LinkTokenRequest(
	@NotBlank(message = "The link is incomplete")
	@Size(max = 200, message = "The link is not valid")
	String token
) {
}
