package com.uniguard.schedule.dto.assignment;

import java.util.List;
import java.util.UUID;

/**
 * People who cannot take a seat at one date and time for reasons the Scheduler page cannot see by itself:
 * an exam in another schedule group at an overlapping time, or leave on that day.
 */
public record BusyElsewhereResponse(List<Person> people) {

	/**
	 * @param invigilator  invigilates in another group at an overlapping time
	 * @param chiefRooms   rooms where the person is chief in another group at an overlapping time
	 * @param onLeave      on leave that day
	 * @param reason       short text for the picker, e.g. "Busy in Spring 2027 at 10:00–12:00"
	 */
	public record Person(UUID personId, boolean invigilator, int chiefRooms, boolean onLeave, String reason) {
	}
}
