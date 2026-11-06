package com.uniguard.schedule.service;

import com.uniguard.schedule.entity.InvigilatorAssignment;
import com.uniguard.schedule.entity.RoomAssignment;
import com.uniguard.schedule.repository.PersonDutyWindow;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Minute-based workload per person within one schedule group (exam period).
 *
 * <p>Fairness rules:
 * <ul>
 *   <li>Only the current exam period counts — last year's heavy load doesn't starve someone now.</li>
 *   <li>Load is measured in minutes, so a 3-hour session weighs more than a 1-hour one.</li>
 *   <li>A chief covering two rooms in the same sitting is counted once for that sitting.</li>
 * </ul>
 */
public final class WorkloadTracker {

	private final Map<UUID, Long> minutes = new HashMap<>();
	private final Map<UUID, Set<String>> sittings = new HashMap<>();

	public static WorkloadTracker fromWindows(Collection<PersonDutyWindow> windows) {
		WorkloadTracker tracker = new WorkloadTracker();
		windows.forEach(w -> tracker.record(w.getPersonId(), w.getExamDate(), w.getStartTime(), w.getEndTime()));
		return tracker;
	}

	public void record(UUID personId, LocalDate date, LocalTime start, LocalTime end) {
		if (personId == null || date == null || start == null || end == null) {
			return;
		}
		String key = date + "@" + start + "-" + end;
		if (sittings.computeIfAbsent(personId, id -> new HashSet<>()).add(key)) {
			minutes.merge(personId, Math.max(Duration.between(start, end).toMinutes(), 0), Long::sum);
		}
	}

	public void record(UUID personId, RoomAssignment assignment) {
		if (assignment.getTimeSlot() == null) {
			return;
		}
		record(personId, assignment.getExamDate(), assignment.getTimeSlot().getStartTime(), assignment.getTimeSlot().getEndTime());
	}

	/** Records every staff member of {@code assignment}. */
	public void recordAll(RoomAssignment assignment) {
		if (assignment.getChiefInvigilator() != null) {
			record(assignment.getChiefInvigilator().getId(), assignment);
		}
		if (assignment.getInvigilatorAssignments() != null) {
			for (InvigilatorAssignment ia : assignment.getInvigilatorAssignments()) {
				if (ia.getInvigilator() != null) {
					record(ia.getInvigilator().getId(), assignment);
				}
			}
		}
	}

	public long minutesFor(UUID personId) {
		return minutes.getOrDefault(personId, 0L);
	}
}
