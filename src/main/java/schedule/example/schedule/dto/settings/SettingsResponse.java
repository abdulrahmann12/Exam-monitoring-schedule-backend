package schedule.example.schedule.dto.settings;

import schedule.example.schedule.entity.enums.ThemeMode;

import java.util.UUID;

public record SettingsResponse(
	UUID id,
	String systemName,
	String appTagline,
	String logoUrl,
	ThemeMode theme,
	String universityName,
	String department,
	String examPeriod
) {
}