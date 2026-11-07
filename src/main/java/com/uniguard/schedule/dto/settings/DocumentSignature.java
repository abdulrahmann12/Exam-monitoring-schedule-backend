package com.uniguard.schedule.dto.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One signature block at the foot of exported schedules: a role title and the person's name. */
public record DocumentSignature(
	@NotBlank(message = "Each signature needs a title, e.g. Head of Schedules Committee")
	@Size(max = 80, message = "A signature title must be at most 80 characters")
	String title,

	@Size(max = 120, message = "A signature name must be at most 120 characters")
	String name
) {
}
