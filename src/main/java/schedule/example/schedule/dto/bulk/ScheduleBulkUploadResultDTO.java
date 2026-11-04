package schedule.example.schedule.dto.bulk;

import java.util.List;

/**
 * Result of a successful schedule bulk upload: the room assignments were created from the
 * workbook and the auto-assignment algorithm then attempted to staff each one.
 *
 * <p>Staffing shortfalls (e.g. no available chief for a room) do NOT fail the request — the room
 * assignments are still created. They are surfaced as {@code warnings} so the caller can follow up.
 */
public record ScheduleBulkUploadResultDTO(
	int roomAssignmentsCreated,
	int chiefSlotsTotal,
	int chiefSlotsFilled,
	int invigilatorSlotsTotal,
	int invigilatorSlotsFilled,
	List<String> warnings
) {
}
