package com.uniguard.schedule.dto.allocation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Proposed rooms for an uploaded exam list (nothing saved yet).
 *
 * @param exams           number of exams (file rows)
 * @param examsFullySeated exams where every student has a seat
 * @param students        students in the file
 * @param studentsUnplaced students without a seat (not enough free rooms)
 * @param roomsUsed       rooms used, counted once per sitting
 * @param seats           seats in the rooms used
 * @param emptySeats      seats left empty in the rooms used
 * @param sittings        one entry per date and time, in time order
 * @param warnings        things to check (e.g. not enough rooms, day name not matching the date)
 * @param timeSlotsToCreate time slots that will be created on confirm (no slot has these times yet)
 * @param columnsRecognised which column of the file was read as what
 * @param booked          rooms already booked outside this plan on the plan's days (room view: no drop there)
 */
public record RoomAllocationPreviewDTO(
	int exams,
	int examsFullySeated,
	int students,
	int studentsUnplaced,
	int roomsUsed,
	int seats,
	int emptySeats,
	List<Sitting> sittings,
	List<String> warnings,
	List<String> timeSlotsToCreate,
	List<String> columnsRecognised,
	List<Booked> booked
) {

	/** A room already booked (saved exam room of any exam period) at that date and time. */
	public record Booked(LocalDate date, String time, UUID roomId, String label) {
	}

	/**
	 * @param timeSlotId null when the time slot does not exist yet
	 * @param freeRooms  free normal rooms (special types such as labs are counted per exam, see {@link Exam#freeOfType})
	 */
	public record Sitting(LocalDate date, String day, String time, UUID timeSlotId, int freeRooms, List<Exam> exams) {
	}

	/**
	 * @param row        the file row (as Excel numbers it)
	 * @param roomType   the room type the exam asked for; null = a normal room
	 * @param freeOfType free rooms of that type at that time (null for normal exams)
	 * @param timeChosen true = the file left the time slot empty and Uni-Guard chose it
	 * @param edited     true = the user changed this exam's time or rooms on the page
	 * @param gender     "Male" / "Female"; null = a mixed exam (any hall)
	 * @param mixedHalls true = a Male/Female exam that needed Mixed halls (not enough halls of its gender)
	 * @param hints      how to fix a problem, worked out from the university's own rooms and times (empty when fine)
	 */
	public record Exam(int row, String subjectName, String subjectCode, int students, String roomType, Integer freeOfType, boolean timeChosen, boolean edited,
		String gender, boolean mixedHalls, int unplaced, List<Room> rooms, List<String> hints) {
	}

	public record Room(UUID roomId, String name, int capacity, int students) {
	}
}
