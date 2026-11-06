package com.uniguard.schedule.dto.settings;

import com.uniguard.schedule.entity.enums.ThemeMode;

import java.util.UUID;

public record SettingsResponse(
	UUID id,
	String systemName,
	String appTagline,
	String logoUrl,
	ThemeMode theme,
	String universityName,
	String department,
	String examPeriod,
	int maxDutiesPerDay,
	int maxDutiesPerPeriod,
	int minRestMinutes,
	int studentsPerInvigilator
) {
}