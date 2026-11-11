package com.uniguard.schedule.dto.allocation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Results of saving the proposed rooms and of "Create schedule". */
public final class RoomAllocationResults {

	private RoomAllocationResults() {
	}

	/**
	 * @param assignmentIds   the exam rooms created (for "Create schedule")
	 * @param examsSaved      exams that got at least one room
	 * @param examsIncomplete exams with students still without a seat (also listed in {@code warnings})
	 * @param firstDate       first exam date (to open the Scheduler there)
	 */
	public record Saved(
		int roomAssignmentsCreated,
		List<UUID> assignmentIds,
		int examsSaved,
		int examsIncomplete,
		int studentsUnplaced,
		List<String> timeSlotsCreated,
		List<String> warnings,
		LocalDate firstDate
	) {
	}

	/** POST /api/room-allocation/staff */
	public record StaffRequest(
		@NotNull(message = "scheduleGroupId is required") UUID scheduleGroupId,
		@NotEmpty(message = "No exam rooms to staff.")
		@Size(max = 5000, message = "At most 5000 exam rooms at once.")
		List<UUID> assignmentIds
	) {
	}

	/**
	 * @param examRooms     exam rooms staffed now
	 * @param alreadyStaffed exam rooms skipped because someone was already assigned (never staffed twice)
	 */
	public record Staffed(
		int examRooms,
		int alreadyStaffed,
		int chiefSlotsTotal,
		int chiefSlotsFilled,
		int invigilatorSlotsTotal,
		int invigilatorSlotsFilled,
		List<String> warnings
	) {
	}
}
