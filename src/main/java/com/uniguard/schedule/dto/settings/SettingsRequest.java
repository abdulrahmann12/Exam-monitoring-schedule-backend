package com.uniguard.schedule.dto.settings;

import com.uniguard.schedule.service.SchedulingSettings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.uniguard.schedule.entity.enums.ThemeMode;

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
	String examPeriod,

	/** Max separate duty sessions per person per day; 0 = unlimited. Omit to keep the current value. */
	@Min(value = 0, message = "Max duties per day cannot be negative")
	@Max(value = 12, message = "Max duties per day must be at most 12")
	Integer maxDutiesPerDay,

	/** Max duty sessions per person per exam period; 0 = unlimited. */
	@Min(value = 0, message = "Max duties per period cannot be negative")
	@Max(value = 500, message = "Max duties per period must be at most 500")
	Integer maxDutiesPerPeriod,

	/** Minimum break between two sessions of one person on the same day, in minutes; 0 = none. */
	@Min(value = 0, message = "Minimum rest cannot be negative")
	@Max(value = 600, message = "Minimum rest must be at most 600 minutes")
	Integer minRestMinutes,

	/** One invigilator per N students; 0 = use each room's minimum. */
	@Min(value = 0, message = "Students per invigilator cannot be negative")
	@Max(value = 1000, message = "Students per invigilator must be at most 1000")
	Integer studentsPerInvigilator,

	/** Rooms one chief invigilator may cover at the same time. Omit to keep the current value. */
	@Min(value = 1, message = "A chief invigilator must be able to cover at least 1 room")
	@Max(value = SchedulingSettings.MAX_CHIEF_PARALLEL_ROOMS, message = "A chief invigilator can cover at most 10 rooms at the same time")
	Integer chiefMaxParallelRooms
) {
}