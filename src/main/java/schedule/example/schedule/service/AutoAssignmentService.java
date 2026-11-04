package schedule.example.schedule.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.assignment.GeneratePreviewRequest;
import schedule.example.schedule.dto.assignment.GeneratePreviewResponse;
import schedule.example.schedule.dto.assignment.PreviewAssignment;
import schedule.example.schedule.entity.InvigilatorAssignment;
import schedule.example.schedule.entity.Person;
import schedule.example.schedule.entity.Room;
import schedule.example.schedule.entity.RoomAssignment;
import schedule.example.schedule.entity.ScheduleGroup;
import schedule.example.schedule.entity.TimeSlot;
import schedule.example.schedule.entity.enums.AssignmentSource;
import schedule.example.schedule.entity.enums.PersonRole;
import schedule.example.schedule.exception.ConflictException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.repository.PersonRepository;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.repository.RoomRepository;
import schedule.example.schedule.repository.TimeSlotRepository;

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
		if (slot.getScheduleGroup() != null && !slot.getScheduleGroup().getId().equals(group.getId())) {
			throw new ConflictException(MessageFormat.format(Messages.ASSIGNMENT_SLOT_GROUP_MISMATCH, group.getName()));
		}
		if (!slot.isActive()) {
			throw new ValidationException(Messages.SLOT_INACTIVE);
		}

		List<PreviewAssignment> current = request.current() == null ? List.of() : request.current();
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
				result.add(toTransient(item, roomsById.get(item.roomId()), slot, group, request, peopleById));
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
			int seats = room.getMinInvigilators();
			if (request.regenerateUnlocked() && previous != null) {
				seats = Math.max(seats, previous.invigilatorIds().size());
			}
			RoomAssignment fresh = newAssignment(room, slot, group, request.examDate());
			if (previous != null) {
				fresh.setSubjectName(previous.subjectName());
				fresh.setSubjectCode(previous.subjectCode());
			}
			fresh.replaceInvigilatorAssignments(emptySeats(Math.max(seats, 0), room.getMinInvigilators()));
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

		List<RoomAssignment> ordered = targets.stream()
			.sorted(Comparator
				.comparing(RoomAssignment::getExamDate)
				.thenComparing(ra -> ra.getTimeSlot().getStartTime())
				.thenComparing(ra -> ra.getRoom().getName()))
			.toList();

		for (RoomAssignment assignment : ordered) {
			String location = describeAssignment(assignment);

			if (assignment.getChiefInvigilator() == null) {
				Person chief = pick(chiefs, true, assignment, context, workload, Set.of());
				if (chief != null) {
					assignment.setChiefInvigilator(chief);
					workload.record(chief.getId(), assignment);
					run.chiefSlotsFilled++;
				} else {
					run.warnings.add(MessageFormat.format(Messages.ASSIGNMENT_NO_CHIEF_AVAILABLE, location));
				}
			}

			Set<UUID> usedInRoom = assignment.getInvigilatorAssignments().stream()
				.map(InvigilatorAssignment::getInvigilator)
				.filter(Objects::nonNull)
				.map(Person::getId)
				.collect(Collectors.toCollection(HashSet::new));

			int missingRequired = 0;
			for (InvigilatorAssignment seat : assignment.getInvigilatorAssignments()) {
				if (seat.isRequired()) {
					run.invigilatorSlotsTotal++;
				}
				if (seat.getInvigilator() != null) {
					continue;
				}
				Person invigilator = pick(invigilators, false, assignment, context, workload, usedInRoom);
				if (invigilator != null) {
					seat.setInvigilator(invigilator);
					usedInRoom.add(invigilator.getId());
					workload.record(invigilator.getId(), assignment);
					if (seat.isRequired()) {
						run.invigilatorSlotsFilled++;
					}
				} else if (seat.isRequired()) {
					missingRequired++;
				}
			}
			if (missingRequired > 0) {
				run.warnings.add(MessageFormat.format(Messages.ASSIGNMENT_INVIGILATORS_UNFILLED, missingRequired, location));
			}
		}
		return run;
	}

	/** Lowest-workload eligible candidate, or {@code null}. */
	private static Person pick(
		List<Person> candidates,
		boolean asChief,
		RoomAssignment current,
		SchedulingContext context,
		WorkloadTracker workload,
		Set<UUID> excludeIds
	) {
		Person best = null;
		long bestLoad = Long.MAX_VALUE;
		for (Person candidate : candidates) {
			if (excludeIds.contains(candidate.getId())) {
				continue;
			}
			if (!StaffConcurrencyRules.canTakeSeat(candidate, asChief, current, context)) {
				continue;
			}
			long load = workload.minutesFor(candidate.getId());
			if (best == null || load < bestLoad || (load == bestLoad && candidate.getName().compareTo(best.getName()) < 0)) {
				best = candidate;
				bestLoad = load;
			}
		}
		return best;
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
		Map<UUID, Person> peopleById
	) {
		RoomAssignment assignment = newAssignment(room, slot, group, request.examDate());
		assignment.setLocked(item.locked());
		assignment.setSubjectName(item.subjectName());
		assignment.setSubjectCode(item.subjectCode());
		assignment.setChiefInvigilator(item.chiefInvigilatorId() == null ? null : peopleById.get(item.chiefInvigilatorId()));
		List<InvigilatorAssignment> seats = new ArrayList<>();
		for (int i = 0; i < item.invigilatorIds().size(); i++) {
			UUID id = item.invigilatorIds().get(i);
			InvigilatorAssignment seat = new InvigilatorAssignment();
			seat.setPositionIndex(i);
			seat.setRequired(i < room.getMinInvigilators());
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
			assignment.getSubjectCode()
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
