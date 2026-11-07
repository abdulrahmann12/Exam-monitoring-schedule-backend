package com.uniguard.schedule.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The secret from the link sent to the new address. */
public record EmailChangeConfirmRequest(
	@NotBlank(message = "The link is not valid")
	@Size(max = 200, message = "The link is not valid")
	String token
) {
}
