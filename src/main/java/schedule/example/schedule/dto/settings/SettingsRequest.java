package schedule.example.schedule.dto.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import schedule.example.schedule.entity.enums.ThemeMode;

public record SettingsRequest(
	@NotBlank(message = "System name is required")
	@Size(max = 160, message = "System name must be at most 160 characters")
	String systemName,

	@Size(max = 200, message = "App tagline must be at most 200 characters")
	String appTagline,

	@Size(max = 500, message = "Logo URL must be at most 500 characters")
	String logoUrl,

	@NotNull(message = "Theme is required")
	ThemeMode theme,

	@NotBlank(message = "University name is required")
	@Size(max = 200, message = "University name must be at most 200 characters")
	String universityName,

	@Size(max = 160, message = "Department must be at most 160 characters")
	String department,

	@NotBlank(message = "Exam period is required")
	@Size(max = 120, message = "Exam period must be at most 120 characters")
	String examPeriod
) {
}