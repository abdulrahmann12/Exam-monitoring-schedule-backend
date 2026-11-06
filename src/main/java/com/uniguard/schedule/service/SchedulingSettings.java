package com.uniguard.schedule.service;

import com.uniguard.schedule.entity.Room;
import com.uniguard.schedule.entity.Settings;

/**
 * Institution-wide scheduling limits (from the settings row). Every value uses 0 = "off".
 *
 * @param maxDutiesPerDay        separate sessions per person per day
 * @param maxDutiesPerPeriod     separate sessions per person per exam period (schedule group)
 * @param minRestMinutes         minimum break between two sessions of one person on a day
 * @param studentsPerInvigilator one invigilator per N students (rooms with a student count)
 */
public record SchedulingSettings(int maxDutiesPerDay, int maxDutiesPerPeriod, int minRestMinutes, int studentsPerInvigilator) {

	public static final SchedulingSettings NONE = new SchedulingSettings(0, 0, 0, 0);

	public SchedulingSettings {
		maxDutiesPerDay = Math.max(maxDutiesPerDay, 0);
		maxDutiesPerPeriod = Math.max(maxDutiesPerPeriod, 0);
		minRestMinutes = Math.max(minRestMinutes, 0);
		studentsPerInvigilator = Math.max(studentsPerInvigilator, 0);
	}

	public static SchedulingSettings from(Settings settings) {
		if (settings == null) {
			return NONE;
		}
		return new SchedulingSettings(
			settings.getMaxDutiesPerDay(),
			settings.getMaxDutiesPerPeriod(),
			settings.getMinRestMinutes(),
			settings.getStudentsPerInvigilator()
		);
	}

	/**
	 * Invigilators a room needs: the room's own minimum, raised to {@code ceil(students / N)}
	 * when a student count is known and a ratio is configured.
	 */
	public int requiredInvigilators(Room room, Integer studentCount) {
		int minimum = Math.max(room.getMinInvigilators(), 0);
		if (studentCount == null || studentCount <= 0 || studentsPerInvigilator <= 0) {
			return minimum;
		}
		int byStudents = (studentCount + studentsPerInvigilator - 1) / studentsPerInvigilator;
		return Math.max(minimum, byStudents);
	}
}
