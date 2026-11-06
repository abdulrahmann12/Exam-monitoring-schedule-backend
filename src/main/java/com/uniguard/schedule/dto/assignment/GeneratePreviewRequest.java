package com.uniguard.schedule.dto.assignment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ask the server to staff one (date, time slot) without saving anything.
 *
 * @param roomIds            rooms to (re)generate
 * @param regenerateUnlocked {@code false}: the slot becomes locked rooms + freshly generated selected rooms.
 *                           {@code true}: unselected rooms in {@code current} are kept as they are.
 * @param current            the client's current draft for this slot (may contain unsaved edits);
 *                           locked rooms are always kept unchanged
 */
public record GeneratePreviewRequest(
	UUID scheduleGroupId,

	@NotNull(message = "Exam date is required")
	LocalDate examDate,

	@NotNull(message = "Slot id is required")
	UUID slotId,

	@NotNull(message = "Room ids are required")
	List<UUID> roomIds,

	boolean regenerateUnlocked,

	List<@Valid PreviewAssignment> current
) {
}
