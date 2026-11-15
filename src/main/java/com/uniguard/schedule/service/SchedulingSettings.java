package com.uniguard.schedule.service;

import com.uniguard.schedule.entity.Person;
import com.uniguard.schedule.entity.Room;
import com.uniguard.schedule.entity.enums.PersonRole;
import com.uniguard.schedule.entity.Settings;

/**
 * Institution-wide scheduling limits (from the settings row). Every value uses 0 = "off".
 *
 * @param maxDutiesPerDay        separate sessions per person per day
 * @param maxDutiesPerPeriod     separate sessions per person per exam period (schedule group)
 * @param minRestMinutes         minimum break between two sessions of one person on a day
 * @param studentsPerInvigilator one invigilator per N students (rooms with a student count)
 * @param chiefMaxParallelRooms  rooms one chief invigilator may cover at the same time (1..10; invigilators: 1)
 * @param examFillPercent        Room Allocation seats at most this % of each room's seats (10..100; 100 = every seat)
 */
public record SchedulingSettings(int maxDutiesPerDay, int maxDutiesPerPeriod, int minRestMinutes, int studentsPerInvigilator,
                                 int chiefMaxParallelRooms, int examFillPercent) {

	public static final int DEFAULT_EXAM_FILL_PERCENT = 100;
	public static final int MIN_EXAM_FILL_PERCENT = 10;
	public static final int DEFAULT_CHIEF_PARALLEL_ROOMS = 2;
	public static final int MAX_CHIEF_PARALLEL_ROOMS = 10;
	public static final SchedulingSettings NONE = new SchedulingSettings(0, 0, 0, 0);

	public SchedulingSettings {
		maxDutiesPerDay = Math.max(maxDutiesPerDay, 0);
		maxDutiesPerPeriod = Math.max(maxDutiesPerPeriod, 0);
		minRestMinutes = Math.max(minRestMinutes, 0);
		studentsPerInvigilator = Math.max(studentsPerInvigilator, 0);
		chiefMaxParallelRooms = chiefMaxParallelRooms <= 0
			? DEFAULT_CHIEF_PARALLEL_ROOMS
			: Math.min(chiefMaxParallelRooms, MAX_CHIEF_PARALLEL_ROOMS);
		examFillPercent = examFillPercent <= 0 || examFillPercent > 100
			? DEFAULT_EXAM_FILL_PERCENT
			: Math.max(examFillPercent, MIN_EXAM_FILL_PERCENT);
	}

	/** Same limits, every seat used for exams. */
	public SchedulingSettings(int maxDutiesPerDay, int maxDutiesPerPeriod, int minRestMinutes, int studentsPerInvigilator,
	                          int chiefMaxParallelRooms) {
		this(maxDutiesPerDay, maxDutiesPerPeriod, minRestMinutes, studentsPerInvigilator, chiefMaxParallelRooms, DEFAULT_EXAM_FILL_PERCENT);
	}

	/** Exam seats of a room: the fill percentage of its capacity, rounded down, at least 1 (70% of 60 = 42). */
	public int examSeats(int capacity) {
		if (capacity <= 0) {
			return 0;
		}
		return Math.max(1, capacity * examFillPercent / 100);
	}

	/** Same limits with the default chief parallel-room limit. */
	public SchedulingSettings(int maxDutiesPerDay, int maxDutiesPerPeriod, int minRestMinutes, int studentsPerInvigilator) {
		this(maxDutiesPerDay, maxDutiesPerPeriod, minRestMinutes, studentsPerInvigilator, DEFAULT_CHIEF_PARALLEL_ROOMS);
	}

	/** Rooms this person may cover at the same time: the setting for chiefs, always 1 for invigilators. */
	public int parallelRoomsFor(Person person) {
		return person.getRole() == PersonRole.CHIEF_INVIGILATOR ? chiefMaxParallelRooms : 1;
	}

	public static SchedulingSettings from(Settings settings) {
		if (settings == null) {
			return NONE;
		}
		return new SchedulingSettings(
			settings.getMaxDutiesPerDay(),
			settings.getMaxDutiesPerPeriod(),
			settings.getMinRestMinutes(),
			settings.getStudentsPerInvigilator(),
			settings.getChiefMaxParallelRooms(),
			settings.getExamFillPercent()
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
