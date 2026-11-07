package com.uniguard.schedule.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.dto.assignment.GeneratePreviewRequest;
import com.uniguard.schedule.dto.assignment.GeneratePreviewResponse;
import com.uniguard.schedule.dto.assignment.PreviewAssignment;
import com.uniguard.schedule.entity.InvigilatorAssignment;
import com.uniguard.schedule.entity.Person;
import com.uniguard.schedule.entity.Room;
import com.uniguard.schedule.entity.RoomAssignment;
import com.uniguard.schedule.entity.ScheduleGroup;
import com.uniguard.schedule.entity.TimeSlot;
import com.uniguard.schedule.entity.enums.AssignmentSource;
import com.uniguard.schedule.entity.enums.PersonRole;
import com.uniguard.schedule.exception.ConflictException;
import com.uniguard.schedule.exception.NotFoundException;
import com.uniguard.schedule.exception.ValidationException;
import com.uniguard.schedule.repository.PersonRepository;
import com.uniguard.schedule.repository.RoomAssignmentRepository;
import com.uniguard.schedule.repository.RoomRepository;
import com.uniguard.schedule.repository.TimeSlotRepository;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The scheduling engine — the only place staff are picked automatically.
 *
 * <p>Two entry points share one algorithm:
 * <ul>
 *   <li>{@link #assignStaff} staffs freshly-created, persisted assignments (Excel schedule upload).</li>
 *   <li>{@link #preview} staffs a draft slot for the Scheduler UI <em>without saving</em>; the admin
 *       reviews/edits it and saves through the normal bulk endpoint, which re-validates everything.</li>
 * </ul>
 *
 * <p>Every candidate is filtered through {@link StaffConcurrencyRules#canTakeSeat}, i.e. exactly the
 * rules enforced on save (cross-group overlaps, leave, parallel-room and daily limits). Among
 * eligible candidates the one with the fewest minutes worked in the current exam period wins
 * ({@link WorkloadTracker}), ties broken by name.
 *
 * <p>Staffing shortages never fail the run — unfilled seats stay empty and are reported as warnings.
 */
@Service
@RequiredArgsConstructor
public class AutoAssignmentService {

	private final PersonRepository personRepository;
	private final RoomRepository roomRepository;
	private final TimeSlotRepository timeSlotRepository;
	private final RoomAssignmentRepository roomAssignmentRepository;
	private final StaffConcurrencyRules staffConcurrencyRules;
	private final SchedulingContextLoader contextLoader;
	private final ScheduleWriteLock scheduleWriteLock;
	private final ScheduleGroupSupport scheduleGroupSupport;

	// ── Persisted staffing (Excel schedule upload) ──────────────────────────

	/**
	 * Assigns chiefs/invigilators to {@code targetAssignments} and persists the result.
	 *
	 * @param scheduleGroupId   the schedule group all assignments belong to
	 * @param targetAssignments already-persisted assignments with no staff yet
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public StaffingResult assignStaff(UUID scheduleGroupId, List<RoomAssignment> targetAssignments) {
		if (targetAssignments.isEmpty()) {
			return new StaffingResult(0, 0, 0, 0, List.of());
		}
		scheduleWriteLock.acquire();

		Set<java.time.LocalDate> examDates = targetAssignments.stream()
			.map(RoomAssignment::getExamDate)
			.collect(Collectors.toSet());
		SchedulingContext context = contextLoader.load(examDates, existing -> containsSame(targetAssignments, existing));
		context.addAll(targetAssignments);

		StaffingRun run = staff(scheduleGroupId, targetAssignments, context);

		List<RoomAssignment> saved = roomAssignmentRepository.saveAll(targetAssignments);
		staffConcurrencyRules.applyWorkloadDelta(Map.of(), StaffConcurrencyRules.countAssignmentOccurrences(saved));

		return new StaffingResult(
			targetAssignments.size(),
			run.chiefSlotsFilled,
			run.invigilatorSlotsTotal,
			run.invigilatorSlotsFilled,
			List.copyOf(run.warnings)
		);
	}

	// ── Preview (Scheduler UI) ──────────────────────────────────────────────

	/** Proposes staff for one (date, slot) draft. Nothing is written to the database. */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public GeneratePreviewResponse preview(GeneratePreviewRequest request) {
		ScheduleGroup group = scheduleGroupSupport.requireGroup(request.scheduleGroupId());
		TimeSlot slot = timeSlotRepository.findById(request.slotId())
			.orElseThrow(() -> new NotFoundException(MessageFormat.format(Messages.SLOT_NOT_FOUND, request.slotId())));
		if (!slot.isActive()) {
			throw new ValidationException(Messages.SLOT_INACTIVE);
		}

		List<PreviewAssignment> current = request.current() == null ? List.of() : request.current();
		SchedulingSettings settings = contextLoader.settings();
		Set<UUID> selectedRoomIds = new LinkedHashSet<>(request.roomIds());

		Set<UUID> roomIds = new HashSet<>(selectedRoomIds);
		current.forEach(item -> roomIds.add(item.roomId()));
		Map<UUID, Room> roomsById = roomRepository.findAllById(roomIds).stream()
			.collect(Collectors.toMap(Room::getId, Function.identity()));
		for (UUID roomId : roomIds) {
			if (!roomsById.containsKey(roomId)) {
				throw new NotFoundException(MessageFormat.format(Messages.ROOM_NOT_FOUND, roomId));
			}
		}

		Set<UUID> personIds = current.stream()
			.flatMap(item -> Stream.concat(Stream.of(item.chiefInvigilatorId()), item.invigilatorIds().stream()))
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		Map<UUID, Person> peopleById = personRepository.findAllById(personIds).stream()
			.collect(Collectors.toMap(Person::getId, Function.identity()));

		// 1. Decide which draft rooms survive unchanged.
		Map<UUID, PreviewAssignment> currentByRoom = new LinkedHashMap<>();
		current.forEach(item -> currentByRoom.putIfAbsent(item.roomId(), item));

		List<RoomAssignment> result = new ArrayList<>();
		List<RoomAssignment> toStaff = new ArrayList<>();
		Set<UUID> handledRooms = new HashSet<>();

		for (PreviewAssignment item : currentByRoom.values()) {
			boolean selected = selectedRoomIds.contains(item.roomId());
			boolean keep = item.locked() || (request.regenerateUnlocked() && !selected);
			if (keep) {
				Room room = roomsById.get(item.roomId());
				result.add(toTransient(item, room, slot, group, request, peopleById, settings.requiredInvigilators(room, item.studentCount())));
				handledRooms.add(item.roomId());
			}
		}

		// 2. Fresh, empty assignments for the selected rooms that weren't kept.
		for (UUID roomId : selectedRoomIds) {
			if (handledRooms.contains(roomId)) {
				continue;
			}
			Room room = roomsById.get(roomId);
			PreviewAssignment previous = currentByRoom.get(roomId);
			Integer studentCount = previous == null ? null : previous.studentCount();
			int required = settings.requiredInvigilators(room, studentCount);
			int seats = required;
			if (request.regenerateUnlocked() && previous != null) {
				seats = Math.max(seats, previous.invigilatorIds().size());
			}
			RoomAssignment fresh = newAssignment(room, slot, group, request.examDate());
			if (previous != null) {
				fresh.setSubjectName(previous.subjectName());
				fresh.setSubjectCode(previous.subjectCode());
				fresh.setStudentCount(previous.studentCount());
			}
			fresh.replaceInvigilatorAssignments(emptySeats(Math.max(seats, 0), required));
			result.add(fresh);
			toStaff.add(fresh);
			handledRooms.add(roomId);
		}

		// 3. Universe: everything on that date in every group, minus this group's persisted copy of
		//    this slot (the draft replaces it), plus the draft itself.
		SchedulingContext context = contextLoader.load(
			List.of(request.examDate()),
			existing -> existing.getScheduleGroup() != null
				&& existing.getScheduleGroup().getId().equals(group.getId())
				&& existing.getTimeSlot().getId().equals(slot.getId())
		);
		context.addAll(result);

		StaffingRun run = staff(group.getId(), toStaff, context);

		// 4. Report every remaining rule violation (including in kept/locked rooms).
		Set<String> conflicts = new LinkedHashSet<>(run.warnings);
		for (RoomAssignment assignment : result) {
			conflicts.addAll(StaffConcurrencyRules.findViolations(assignment, context));
		}

		List<PreviewAssignment> response = result.stream()
			.sorted(Comparator.comparing(ra -> ra.getRoom().getName()))
			.map(AutoAssignmentService::toPreview)
			.toList();
		return new GeneratePreviewResponse(response, List.copyOf(conflicts));
	}

	// ── Algorithm ───────────────────────────────────────────────────────────

	/**
	 * Fills empty chief/invigilator seats of {@code targets}. Targets must already be in
	 * {@code context.universe()}. Mutates the targets in memory only.
	 *
	 * <p>Works sitting by sitting (same date and time window), chronologically. Within a sitting the
	 * seats are filled with a maximum matching ({@link SeatMatcher}), so the number of filled seats
	 * is the best possible, while the least-loaded eligible people are preferred.
	 */
	private StaffingRun staff(UUID scheduleGroupId, List<RoomAssignment> targets, SchedulingContext context) {
		StaffingRun run = new StaffingRun();
		if (targets.isEmpty()) {
			return run;
		}

		List<Person> chiefs = personRepository.findAllByRoleAndActiveTrue(PersonRole.CHIEF_INVIGILATOR);
		List<Person> invigilators = personRepository.findAllByRoleAndActiveTrue(PersonRole.INVIGILATOR);

		WorkloadTracker workload = WorkloadTracker.fromWindows(Stream.concat(
			roomAssignmentRepository.findChiefDutyWindowsByScheduleGroupId(scheduleGroupId).stream(),
			roomAssignmentRepository.findInvigilatorDutyWindowsByScheduleGroupId(scheduleGroupId).stream()
		).toList());
		// Draft/in-memory assignments of this group that aren't persisted yet also count.
		// (Never use List.contains/equals on entities here: Lombok @Data equals walks the object graph.)
		context.universe().stream()
			.filter(ra -> ra.getId() == null)
			.filter(ra -> ra.getScheduleGroup() != null && scheduleGroupId.equals(ra.getScheduleGroup().getId()))
			.forEach(workload::recordAll);

		// Every room gets at least the number of invigilator seats it requires.
		SchedulingSettings settings = context.settings();
		for (RoomAssignment target : targets) {
			ensureSeats(target, settings.requiredInvigilators(target.getRoom(), target.getStudentCount()));
		}

		Map<String, List<RoomAssignment>> sittings = new java.util.TreeMap<>();
		for (RoomAssignment target : targets) {
			String key = target.getExamDate() + "@" + target.getTimeSlot().getStartTime() + "-" + target.getTimeSlot().getEndTime();
			sittings.computeIfAbsent(key, k -> new ArrayList<>()).add(target);
		}

		for (List<RoomAssignment> sitting : sittings.values()) {
			sitting.sort(Comparator.comparing(ra -> ra.getRoom().getName()));
			fillChiefs(sitting, chiefs, context, workload, run);
			fillInvigilators(sitting, invigilators, context, workload, run);
		}
		return run;
	}

	private static void fillChiefs(List<RoomAssignment> sitting, List<Person> chiefs, SchedulingContext context,
	                               WorkloadTracker workload, StaffingRun run) {
		List<RoomAssignment> seats = sitting.stream().filter(ra -> ra.getChiefInvigilator() == null).toList();
		if (seats.isEmpty()) {
			return;
		}
		List<Person> ordered = byWorkload(chiefs, workload);
		int[] capacity = new int[ordered.size()];
		for (int p = 0; p < ordered.size(); p++) {
			Person chief = ordered.get(p);
			// Rooms already held at this time (outside these empty seats) reduce what is left.
			int held = StaffConcurrencyRules.countOverlappingChiefRooms(context.universe(), seats.getFirst(), chief.getId());
			capacity[p] = Math.max(context.settings().parallelRoomsFor(chief) - held, 0);
		}
		List<List<Integer>> candidates = new ArrayList<>(seats.size());
		for (RoomAssignment seat : seats) {
			List<Integer> eligible = new ArrayList<>();
			for (int p = 0; p < ordered.size(); p++) {
				if (capacity[p] > 0 && StaffConcurrencyRules.canTakeSeat(ordered.get(p), true, seat, context)) {
					eligible.add(p);
				}
			}
			candidates.add(eligible);
		}
		// Pass 1: at most one room per chief (spreads the work). Pass 2: use second rooms only for
		// seats that are still empty. Pass 2 only augments pass 1, so coverage stays maximal.
		int[] oneEach = new int[capacity.length];
		for (int p = 0; p < capacity.length; p++) {
			oneEach[p] = Math.min(capacity[p], 1);
		}
		int[] result = SeatMatcher.match(candidates, capacity, SeatMatcher.match(candidates, oneEach));
		for (int s = 0; s < seats.size(); s++) {
			RoomAssignment seat = seats.get(s);
			if (result[s] >= 0) {
				Person chief = ordered.get(result[s]);
				seat.setChiefInvigilator(chief);
				workload.record(chief.getId(), seat);
				run.chiefSlotsFilled++;
			} else {
				run.warnings.add(MessageFormat.format(Messages.ASSIGNMENT_NO_CHIEF_AVAILABLE, describeAssignment(seat)));
			}
		}
	}

	private static void fillInvigilators(List<RoomAssignment> sitting, List<Person> invigilators, SchedulingContext context,
	                                     WorkloadTracker workload, StaffingRun run) {
		record Seat(RoomAssignment room, InvigilatorAssignment slot) {
		}
		List<Seat> required = new ArrayList<>();
		List<Seat> optional = new ArrayList<>();
		for (RoomAssignment room : sitting) {
			for (InvigilatorAssignment slot : room.getInvigilatorAssignments()) {
				if (slot.isRequired()) {
					run.invigilatorSlotsTotal++;
				}
				if (slot.getInvigilator() == null) {
					(slot.isRequired() ? required : optional).add(new Seat(room, slot));
				}
			}
		}
		List<Seat> seats = new ArrayList<>(required);
		seats.addAll(optional); // mandatory seats are matched first and never emptied again
		if (seats.isEmpty()) {
			return;
		}

		List<Person> ordered = byWorkload(invigilators, workload);
		int[] capacity = new int[ordered.size()];
		java.util.Arrays.fill(capacity, 1);
		Map<RoomAssignment, List<Integer>> eligibleByRoom = new java.util.IdentityHashMap<>();
		List<List<Integer>> candidates = new ArrayList<>(seats.size());
		for (Seat seat : seats) {
			candidates.add(eligibleByRoom.computeIfAbsent(seat.room(), room -> {
				Set<UUID> alreadyInRoom = room.getInvigilatorAssignments().stream()
					.map(InvigilatorAssignment::getInvigilator)
					.filter(Objects::nonNull)
					.map(Person::getId)
					.collect(Collectors.toSet());
				List<Integer> eligible = new ArrayList<>();
				for (int p = 0; p < ordered.size(); p++) {
					Person person = ordered.get(p);
					if (!alreadyInRoom.contains(person.getId()) && StaffConcurrencyRules.canTakeSeat(person, false, room, context)) {
						eligible.add(p);
					}
				}
				return eligible;
			}));
		}
		int[] result = SeatMatcher.match(candidates, capacity);

		Map<RoomAssignment, Integer> missingRequired = new java.util.IdentityHashMap<>();
		for (int s = 0; s < seats.size(); s++) {
			Seat seat = seats.get(s);
			if (result[s] >= 0) {
				Person person = ordered.get(result[s]);
				seat.slot().setInvigilator(person);
				workload.record(person.getId(), seat.room());
				if (seat.slot().isRequired()) {
					run.invigilatorSlotsFilled++;
				}
			} else if (seat.slot().isRequired()) {
				missingRequired.merge(seat.room(), 1, Integer::sum);
			}
		}
		for (RoomAssignment room : sitting) {
			Integer missing = missingRequired.get(room);
			if (missing != null) {
				run.warnings.add(MessageFormat.format(Messages.ASSIGNMENT_INVIGILATORS_UNFILLED, missing, describeAssignment(room)));
			}
		}
	}

	/** Candidates sorted by minutes worked in this exam period, then by name. */
	private static List<Person> byWorkload(List<Person> people, WorkloadTracker workload) {
		return people.stream()
			.sorted(Comparator.comparingLong((Person p) -> workload.minutesFor(p.getId())).thenComparing(Person::getName))
			.toList();
	}

	/** Adds empty seats until the room has {@code required}; marks the first {@code required} as mandatory. */
	private static void ensureSeats(RoomAssignment assignment, int required) {
		List<InvigilatorAssignment> seats = assignment.getInvigilatorAssignments();
		for (int i = seats.size(); i < required; i++) {
			InvigilatorAssignment seat = new InvigilatorAssignment();
			seat.setPositionIndex(i);
			assignment.addInvigilatorAssignment(seat);
		}
		for (int i = 0; i < seats.size(); i++) {
			seats.get(i).setRequired(i < required);
		}
	}

	// ── Helpers ─────────────────────────────────────────────────────────────

	private static boolean containsSame(List<RoomAssignment> list, RoomAssignment candidate) {
		for (RoomAssignment item : list) {
			if (item == candidate || (item.getId() != null && item.getId().equals(candidate.getId()))) {
				return true;
			}
		}
		return false;
	}

	private static RoomAssignment newAssignment(Room room, TimeSlot slot, ScheduleGroup group, java.time.LocalDate date) {
		RoomAssignment assignment = new RoomAssignment();
		assignment.setRoom(room);
		assignment.setTimeSlot(slot);
		assignment.setScheduleGroup(group);
		assignment.setExamDate(date);
		assignment.setSource(AssignmentSource.GENERATED);
		return assignment;
	}

	private static List<InvigilatorAssignment> emptySeats(int count, int required) {
		List<InvigilatorAssignment> seats = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			InvigilatorAssignment seat = new InvigilatorAssignment();
			seat.setPositionIndex(i);
			seat.setRequired(i < required);
			seats.add(seat);
		}
		return seats;
	}

	private static RoomAssignment toTransient(
		PreviewAssignment item,
		Room room,
		TimeSlot slot,
		ScheduleGroup group,
		GeneratePreviewRequest request,
		Map<UUID, Person> peopleById,
		int requiredSeats
	) {
		RoomAssignment assignment = newAssignment(room, slot, group, request.examDate());
		assignment.setLocked(item.locked());
		assignment.setStudentCount(item.studentCount());
		assignment.setSubjectName(item.subjectName());
		assignment.setSubjectCode(item.subjectCode());
		assignment.setChiefInvigilator(item.chiefInvigilatorId() == null ? null : peopleById.get(item.chiefInvigilatorId()));
		List<InvigilatorAssignment> seats = new ArrayList<>();
		for (int i = 0; i < item.invigilatorIds().size(); i++) {
			UUID id = item.invigilatorIds().get(i);
			InvigilatorAssignment seat = new InvigilatorAssignment();
			seat.setPositionIndex(i);
			seat.setRequired(i < requiredSeats);
			seat.setInvigilator(id == null ? null : peopleById.get(id));
			seats.add(seat);
		}
		assignment.replaceInvigilatorAssignments(seats);
		return assignment;
	}

	private static PreviewAssignment toPreview(RoomAssignment assignment) {
		List<UUID> invigilatorIds = assignment.getInvigilatorAssignments().stream()
			.map(seat -> seat.getInvigilator() == null ? null : seat.getInvigilator().getId())
			.collect(Collectors.toList());
		return new PreviewAssignment(
			assignment.getRoom().getId(),
			assignment.getChiefInvigilator() == null ? null : assignment.getChiefInvigilator().getId(),
			invigilatorIds,
			assignment.isLocked(),
			assignment.getSubjectName(),
			assignment.getSubjectCode(),
			assignment.getStudentCount()
		);
	}

	private static String describeAssignment(RoomAssignment assignment) {
		return "room '" + assignment.getRoom().getName() + "' on " + assignment.getExamDate()
			+ " (" + StaffConcurrencyRules.describeSlot(assignment.getTimeSlot()) + ")";
	}

	private static final class StaffingRun {
		int chiefSlotsFilled;
		int invigilatorSlotsTotal;
		int invigilatorSlotsFilled;
		final List<String> warnings = new ArrayList<>();
	}

	/** Outcome of one {@link #assignStaff} run — staffing counts only; room-assignment creation is the caller's concern. */
	public record StaffingResult(
		int chiefSlotsTotal,
		int chiefSlotsFilled,
		int invigilatorSlotsTotal,
		int invigilatorSlotsFilled,
		List<String> warnings
	) {
	}
}
