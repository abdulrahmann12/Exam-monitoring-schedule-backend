package com.uniguard.schedule.service;

import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.dto.assignment.BulkRoomAssignmentRequest;
import com.uniguard.schedule.dto.assignment.GeneratePreviewRequest;
import com.uniguard.schedule.dto.assignment.GeneratePreviewResponse;
import com.uniguard.schedule.dto.assignment.RoomAssignmentRequest;
import com.uniguard.schedule.dto.assignment.RoomAssignmentResponse;
import com.uniguard.schedule.dto.common.PageResponse;
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
import com.uniguard.schedule.mapper.RoomAssignmentMapper;
import com.uniguard.schedule.repository.PersonRepository;
import com.uniguard.schedule.repository.RoomAssignmentRepository;
import com.uniguard.schedule.repository.RoomRepository;
import com.uniguard.schedule.repository.TimeSlotRepository;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssignmentService {

    private final RoomAssignmentRepository roomAssignmentRepository;
    private final RoomRepository roomRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final PersonRepository personRepository;
    private final RoomAssignmentMapper roomAssignmentMapper;
    private final DemoModeService demoModeService;
    private final ScheduleGroupSupport scheduleGroupSupport;
    private final StaffConcurrencyRules staffConcurrencyRules;
    private final SchedulingContextLoader contextLoader;
    private final ScheduleWriteLock scheduleWriteLock;
    private final AutoAssignmentService autoAssignmentService;


    public PageResponse<RoomAssignmentResponse> getAssignments(
            UUID scheduleGroupId, UUID slotId, UUID roomId, Boolean locked,
            LocalDate fromDate, LocalDate toDate, Pageable pageable) {
        UUID groupId = scheduleGroupSupport.requireGroup(scheduleGroupId).getId();
        Page<UUID> assignmentIds = roomAssignmentRepository.findIdsByFilters(
                groupId, slotId, roomId, locked, fromDate, toDate, pageable);
        List<RoomAssignmentResponse> ordered = getOrderedAssignmentResponses(assignmentIds.getContent());
        return PageResponse.from(new PageImpl<>(ordered, pageable, assignmentIds.getTotalElements()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RoomAssignmentResponse createAssignment(@Valid RoomAssignmentRequest request) {
        scheduleWriteLock.acquire();
        RoomAssignment assignment = roomAssignmentMapper.toEntity(request);
        configureAssignmentRelations(assignment, request);
        validateAssignmentRules(assignment, null);

        RoomAssignment saved = roomAssignmentRepository.save(assignment);
        staffConcurrencyRules.applyWorkloadDelta(Map.of(), StaffConcurrencyRules.countAssignmentOccurrences(saved));

        // Map directly from the already-populated entity — no redundant EntityGraph re-fetch.
        return roomAssignmentMapper.toResponse(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RoomAssignmentResponse updateAssignment(UUID id, @Valid RoomAssignmentRequest request) {
        scheduleWriteLock.acquire();
        // Fetch once with full EntityGraph — all relations are in the session.
        RoomAssignment assignment = getDetailedAssignment(id);
        Map<UUID, Integer> previousOccurrences = StaffConcurrencyRules.countAssignmentOccurrences(assignment);

        roomAssignmentMapper.updateEntity(request, assignment);
        configureAssignmentRelations(assignment, request);
        validateAssignmentRules(assignment, id);

        roomAssignmentRepository.save(assignment);
        staffConcurrencyRules.applyWorkloadDelta(previousOccurrences, StaffConcurrencyRules.countAssignmentOccurrences(assignment));

        // Relations are still in the session from the initial fetch — map directly, no re-fetch.
        return roomAssignmentMapper.toResponse(assignment);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<RoomAssignmentResponse> saveAssignmentsBulk(List<BulkRoomAssignmentRequest> requests) {
        demoModeService.rejectIfDemoUser("bulk-save-assignments");
        if (requests == null || requests.isEmpty()) {
            throw new ValidationException(Messages.ASSIGNMENT_BULK_EMPTY);
        }
        scheduleWriteLock.acquire();

        Map<UUID, Room> roomsById = loadRooms(requests);
        Map<UUID, TimeSlot> timeSlotsById = loadTimeSlots(requests);
        Map<UUID, Person> peopleById = loadPeople(requests);
        ScheduleGroup bulkGroup = resolveBulkGroup(requests, timeSlotsById);
        List<RoomAssignment> existingAssignments = loadExistingAssignments(requests, bulkGroup.getId());
        Map<AssignmentCompositeKey, RoomAssignment> existingByKey = existingAssignments.stream()
                .collect(Collectors.toMap(AssignmentCompositeKey::from, Function.identity()));

        validateRequestKeys(requests);

        Map<UUID, Integer> previousOccurrences = StaffConcurrencyRules.countAssignmentOccurrences(existingAssignments);
        List<RoomAssignment> preparedAssignments = new ArrayList<>(requests.size());

        for (BulkRoomAssignmentRequest request : requests) {
            AssignmentCompositeKey key = AssignmentCompositeKey.from(request);
            RoomAssignment assignment = existingByKey.getOrDefault(key, new RoomAssignment());
            configureBulkAssignmentRelations(assignment, request, roomsById, timeSlotsById, peopleById, bulkGroup);
            preparedAssignments.add(assignment);
        }

        validateBulkAssignmentRules(preparedAssignments, bulkGroup.getId());

        Set<AssignmentCompositeKey> preparedKeys = preparedAssignments.stream()
                .map(AssignmentCompositeKey::from)
                .collect(Collectors.toSet());

        List<RoomAssignment> toDelete = existingAssignments.stream()
                .filter(existing -> !preparedKeys.contains(AssignmentCompositeKey.from(existing)))
                .toList();

        // Locked rooms are protected: they must be unlocked explicitly before they can disappear.
        toDelete.stream().filter(RoomAssignment::isLocked).findFirst().ifPresent(locked -> {
            throw new ConflictException(MessageFormat.format(
                    Messages.ASSIGNMENT_LOCKED_DELETE, locked.getRoom().getName(), locked.getExamDate()));
        });

        if (!toDelete.isEmpty()) {
            roomAssignmentRepository.deleteAll(toDelete);
        }

        // saveAll() sends batched INSERTs/UPDATEs in groups of hibernate.jdbc.batch_size (50).
        List<RoomAssignment> savedAssignments = roomAssignmentRepository.saveAll(preparedAssignments);

        Map<UUID, Integer> nextOccurrences = StaffConcurrencyRules.countAssignmentOccurrences(savedAssignments);
        staffConcurrencyRules.applyWorkloadDelta(previousOccurrences, nextOccurrences);

        // Map directly from the in-session entities — all relations already populated by
        // configureBulkAssignmentRelations(). Avoids the previous redundant EntityGraph re-fetch.
        return savedAssignments.stream()
                .filter(ra -> ra.getId() != null)
                .map(roomAssignmentMapper::toResponse)
                .toList();
    }

    /** Server-side generation of a draft slot (nothing saved) — see {@link AutoAssignmentService#preview}. */
    public GeneratePreviewResponse generatePreview(@Valid GeneratePreviewRequest request) {
        return autoAssignmentService.preview(request);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteAssignment(UUID id) {
        demoModeService.rejectIfDemoUser("delete-assignment");
        scheduleWriteLock.acquire();
        RoomAssignment assignment = getDetailedAssignment(id);
        Map<UUID, Integer> previousOccurrences = StaffConcurrencyRules.countAssignmentOccurrences(assignment);
        roomAssignmentRepository.delete(assignment);
        staffConcurrencyRules.applyWorkloadDelta(previousOccurrences, Map.of());
    }

    // ─── Private helpers ────────────────────────────────────────────────────────

    private List<RoomAssignmentResponse> getOrderedAssignmentResponses(Collection<UUID> assignmentIds) {
        if (assignmentIds.isEmpty()) return List.of();
        Map<UUID, RoomAssignmentResponse> byId = roomAssignmentRepository
                .findAllDetailedByIdIn(assignmentIds).stream()
                .collect(Collectors.toMap(RoomAssignment::getId, roomAssignmentMapper::toResponse));
        return assignmentIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private RoomAssignment getDetailedAssignment(UUID id) {
        return roomAssignmentRepository.findDetailedById(id)
                .orElseThrow(() -> new NotFoundException(MessageFormat.format(Messages.ASSIGNMENT_NOT_FOUND, id)));
    }

    private Map<UUID, Room> loadRooms(List<BulkRoomAssignmentRequest> requests) {
        Set<UUID> roomIds = requests.stream()
                .map(BulkRoomAssignmentRequest::roomId)
                .collect(Collectors.toSet());
        Map<UUID, Room> roomsById = roomRepository.findAllById(roomIds).stream()
                .collect(Collectors.toMap(Room::getId, Function.identity()));

        for (UUID roomId : roomIds) {
            if (!roomsById.containsKey(roomId)) {
                throw new NotFoundException(MessageFormat.format(Messages.ROOM_NOT_FOUND, roomId));
            }
        }

        return roomsById;
    }

    private Map<UUID, TimeSlot> loadTimeSlots(List<BulkRoomAssignmentRequest> requests) {
        Set<UUID> slotIds = requests.stream()
                .map(BulkRoomAssignmentRequest::slotId)
                .collect(Collectors.toSet());
        Map<UUID, TimeSlot> timeSlotsById = timeSlotRepository.findAllById(slotIds).stream()
                .collect(Collectors.toMap(TimeSlot::getId, Function.identity()));

        for (UUID slotId : slotIds) {
            TimeSlot timeSlot = timeSlotsById.get(slotId);
            if (timeSlot == null) {
                throw new NotFoundException(MessageFormat.format(Messages.SLOT_NOT_FOUND, slotId));
            }
            if (!timeSlot.isActive()) {
                throw new ValidationException(Messages.SLOT_INACTIVE);
            }
            if (!timeSlot.getEndTime().isAfter(timeSlot.getStartTime())) {
                throw new ValidationException(Messages.SLOT_INVALID_RANGE);
            }
        }

        return timeSlotsById;
    }

    private Map<UUID, Person> loadPeople(List<BulkRoomAssignmentRequest> requests) {
        Set<UUID> personIds = new HashSet<>();
        requests.forEach(request -> {
            if (request.chiefInvigilatorId() != null) {
                personIds.add(request.chiefInvigilatorId());
            }
            request.invigilatorIds().stream()
                    .filter(Objects::nonNull)
                    .forEach(personIds::add);
        });

        return personRepository.findAllById(personIds).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
    }

    private List<RoomAssignment> loadExistingAssignments(List<BulkRoomAssignmentRequest> requests, UUID groupId) {
        Set<LocalDate> examDates = requests.stream()
                .map(BulkRoomAssignmentRequest::examDate)
                .collect(Collectors.toSet());
        Set<UUID> slotIds = requests.stream()
                .map(BulkRoomAssignmentRequest::slotId)
                .collect(Collectors.toSet());

        return roomAssignmentRepository.findAllDetailedByExamDateInAndTimeSlotIdIn(groupId, examDates, slotIds);
    }

    private void validateRequestKeys(List<BulkRoomAssignmentRequest> requests) {
        Set<AssignmentCompositeKey> keys = new HashSet<>();
        for (BulkRoomAssignmentRequest request : requests) {
            AssignmentCompositeKey key = AssignmentCompositeKey.from(request);
            if (!keys.add(key)) {
                throw new ValidationException(MessageFormat.format(
                        Messages.ASSIGNMENT_BULK_DUPLICATE_ROOM_SLOT,
                        request.roomId(),
                        request.slotId(),
                        request.examDate()
                ));
            }
        }
    }

    private void configureAssignmentRelations(RoomAssignment assignment, RoomAssignmentRequest request) {
        Room room = roomRepository.findById(request.roomId())
                .orElseThrow(() -> new NotFoundException(MessageFormat.format(Messages.ROOM_NOT_FOUND, request.roomId())));

        TimeSlot timeSlot = timeSlotRepository.findById(request.timeSlotId())
                .orElseThrow(() -> new NotFoundException(MessageFormat.format(Messages.SLOT_NOT_FOUND, request.timeSlotId())));

        if (!timeSlot.isActive()) {
            throw new ValidationException(Messages.SLOT_INACTIVE);
        }
        if (!timeSlot.getEndTime().isAfter(timeSlot.getStartTime())) {
            throw new ValidationException(Messages.SLOT_INVALID_RANGE);
        }
        if (request.examDate() == null) {
            throw new ValidationException(Messages.ASSIGNMENT_SLOT_DATE_REQUIRED);
        }

        Person chiefInvigilator = resolveChiefInvigilator(request.chiefInvigilatorId());
        List<Person> invigilators = resolveInvigilators(request.invigilatorIds());

        assignment.setExamDate(request.examDate());
        assignment.setRoom(room);
        assignment.setTimeSlot(timeSlot);
        assignment.setScheduleGroup(resolveAssignmentGroup(request.scheduleGroupId(), timeSlot, assignment.getScheduleGroup()));
        assignment.setChiefInvigilator(chiefInvigilator);
        assignment.setLocked(Boolean.TRUE.equals(request.locked()));
        assignment.setSource(request.source() != null ? request.source() : AssignmentSource.MANUAL);
        assignment.setStudentCount(validateStudentCount(room, request.studentCount()));
        assignment.replaceInvigilatorAssignments(buildInvigilatorAssignments(invigilators,
                contextLoader.settings().requiredInvigilators(room, request.studentCount())));
    }

    private void configureBulkAssignmentRelations(
            RoomAssignment assignment,
            BulkRoomAssignmentRequest request,
            Map<UUID, Room> roomsById,
            Map<UUID, TimeSlot> timeSlotsById,
            Map<UUID, Person> peopleById,
            ScheduleGroup group
    ) {
        if (request.examDate() == null) {
            throw new ValidationException(Messages.ASSIGNMENT_SLOT_DATE_REQUIRED);
        }

        Room room = roomsById.get(request.roomId());
        if (room == null) {
            throw new NotFoundException(MessageFormat.format(Messages.ROOM_NOT_FOUND, request.roomId()));
        }

        TimeSlot timeSlot = timeSlotsById.get(request.slotId());
        if (timeSlot == null) {
            throw new NotFoundException(MessageFormat.format(Messages.SLOT_NOT_FOUND, request.slotId()));
        }

        Person chiefInvigilator = resolveChiefInvigilator(request.chiefInvigilatorId(), peopleById);
        List<Person> invigilators = resolveInvigilators(request.invigilatorIds(), peopleById);

        assignment.setExamDate(request.examDate());
        assignment.setRoom(room);
        assignment.setTimeSlot(timeSlot);
        assertSlotBelongsToGroup(timeSlot, group);
        assignment.setScheduleGroup(group);
        assignment.setChiefInvigilator(chiefInvigilator);
        assignment.setLocked(Boolean.TRUE.equals(request.isLocked()));
        assignment.setSource(AssignmentSource.GENERATED);
        assignment.setStudentCount(validateStudentCount(room, request.studentCount()));
        assignment.replaceInvigilatorAssignments(buildInvigilatorAssignments(invigilators,
                contextLoader.settings().requiredInvigilators(room, request.studentCount())));
    }

    private ScheduleGroup resolveAssignmentGroup(UUID requestedGroupId, TimeSlot timeSlot, ScheduleGroup existing) {
        ScheduleGroup group;
        if (requestedGroupId != null) {
            group = scheduleGroupSupport.requireGroup(requestedGroupId);
        } else if (existing != null) {
            group = existing;
        } else if (timeSlot.getScheduleGroup() != null) {
            group = timeSlot.getScheduleGroup();
        } else {
            group = scheduleGroupSupport.getOrCreateDefaultGroup();
        }
        assertSlotBelongsToGroup(timeSlot, group);
        return group;
    }

    private ScheduleGroup resolveBulkGroup(List<BulkRoomAssignmentRequest> requests, Map<UUID, TimeSlot> timeSlotsById) {
        Set<UUID> groupIds = new HashSet<>();
        for (BulkRoomAssignmentRequest request : requests) {
            if (request.scheduleGroupId() != null) {
                groupIds.add(request.scheduleGroupId());
                continue;
            }
            TimeSlot slot = timeSlotsById.get(request.slotId());
            if (slot != null && slot.getScheduleGroup() != null) {
                groupIds.add(slot.getScheduleGroup().getId());
            }
        }
        if (groupIds.size() > 1) {
            throw new ValidationException(Messages.ASSIGNMENT_BULK_MIXED_GROUPS);
        }
        if (groupIds.size() == 1) {
            return scheduleGroupSupport.requireGroup(groupIds.iterator().next());
        }
        return scheduleGroupSupport.getOrCreateDefaultGroup();
    }

    private void assertSlotBelongsToGroup(TimeSlot timeSlot, ScheduleGroup group) {
        if (timeSlot.getScheduleGroup() == null) {
            timeSlot.setScheduleGroup(group);
            return;
        }
        if (!timeSlot.getScheduleGroup().getId().equals(group.getId())) {
            throw new ConflictException(MessageFormat.format(
                    Messages.ASSIGNMENT_SLOT_GROUP_MISMATCH, group.getName()));
        }
    }

    private Person resolveChiefInvigilator(UUID chiefInvigilatorId) {
        if (chiefInvigilatorId == null) return null;
        Person chief = personRepository.findById(chiefInvigilatorId)
                .orElseThrow(() -> new NotFoundException(MessageFormat.format(Messages.PERSON_NOT_FOUND, chiefInvigilatorId)));
        if (chief.getRole() != PersonRole.CHIEF_INVIGILATOR) {
            throw new ValidationException(MessageFormat.format(Messages.ASSIGNMENT_CHIEF_ROLE_INVALID, chief.getName()));
        }
        return chief;
    }

    private Person resolveChiefInvigilator(UUID chiefInvigilatorId, Map<UUID, Person> peopleById) {
        if (chiefInvigilatorId == null) {
            return null;
        }

        Person chief = peopleById.get(chiefInvigilatorId);
        if (chief == null) {
            throw new NotFoundException(MessageFormat.format(Messages.PERSON_NOT_FOUND, chiefInvigilatorId));
        }
        if (chief.getRole() != PersonRole.CHIEF_INVIGILATOR) {
            throw new ValidationException(MessageFormat.format(Messages.ASSIGNMENT_CHIEF_ROLE_INVALID, chief.getName()));
        }

        return chief;
    }

    private List<Person> resolveInvigilators(List<UUID> invigilatorIds) {
        List<UUID> nonNull = invigilatorIds.stream().filter(Objects::nonNull).toList();
        if (new HashSet<>(nonNull).size() != nonNull.size()) {
            throw new ValidationException(Messages.ASSIGNMENT_INVIGILATOR_DUPLICATE);
        }
        Map<UUID, Person> peopleById = personRepository.findAllById(nonNull).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        for (UUID id : nonNull) {
            if (!peopleById.containsKey(id)) {
                throw new NotFoundException(MessageFormat.format(Messages.PERSON_NOT_FOUND, id));
            }
            Person p = peopleById.get(id);
            if (p.getRole() != PersonRole.INVIGILATOR) {
                throw new ValidationException(MessageFormat.format(Messages.ASSIGNMENT_INVIGILATOR_ROLE_INVALID, p.getName()));
            }
        }
        List<Person> ordered = new ArrayList<>(invigilatorIds.size());
        for (UUID id : invigilatorIds) {
            ordered.add(id == null ? null : peopleById.get(id));
        }
        return ordered;
    }

    private List<Person> resolveInvigilators(List<UUID> invigilatorIds, Map<UUID, Person> peopleById) {
        List<UUID> nonNull = invigilatorIds.stream().filter(Objects::nonNull).toList();
        if (new HashSet<>(nonNull).size() != nonNull.size()) {
            throw new ValidationException(Messages.ASSIGNMENT_INVIGILATOR_DUPLICATE);
        }

        List<Person> ordered = new ArrayList<>(invigilatorIds.size());
        for (UUID invigilatorId : invigilatorIds) {
            if (invigilatorId == null) {
                ordered.add(null);
                continue;
            }

            Person person = peopleById.get(invigilatorId);
            if (person == null) {
                throw new NotFoundException(MessageFormat.format(Messages.PERSON_NOT_FOUND, invigilatorId));
            }
            if (person.getRole() != PersonRole.INVIGILATOR) {
                throw new ValidationException(MessageFormat.format(Messages.ASSIGNMENT_INVIGILATOR_ROLE_INVALID, person.getName()));
            }
            ordered.add(person);
        }

        return ordered;
    }

    /** Student count is optional, but can never exceed the room's capacity. */
    private static Integer validateStudentCount(Room room, Integer studentCount) {
        if (studentCount != null && studentCount > room.getCapacity()) {
            throw new ValidationException(MessageFormat.format(
                    Messages.ASSIGNMENT_STUDENTS_EXCEED_CAPACITY, room.getName(), room.getCapacity(), studentCount));
        }
        return studentCount;
    }

    private List<InvigilatorAssignment> buildInvigilatorAssignments(List<Person> invigilators, int minRequired) {
        List<InvigilatorAssignment> result = new ArrayList<>(invigilators.size());
        for (int i = 0; i < invigilators.size(); i++) {
            InvigilatorAssignment ia = new InvigilatorAssignment();
            ia.setInvigilator(invigilators.get(i));
            ia.setPositionIndex(i);
            ia.setRequired(i < minRequired);
            result.add(ia);
        }
        return result;
    }

    private void validateAssignmentRules(RoomAssignment assignment, UUID existingId) {
        if (assignment.getScheduleGroup() == null) {
            throw new ValidationException(Messages.ASSIGNMENT_GROUP_REQUIRED);
        }

        UUID groupId = assignment.getScheduleGroup().getId();
        UUID roomId = assignment.getRoom().getId();
        UUID timeSlotId = assignment.getTimeSlot().getId();
        LocalDate examDate = assignment.getExamDate();

        boolean duplicate = existingId == null
                ? roomAssignmentRepository.existsByScheduleGroupIdAndRoomIdAndTimeSlotIdAndExamDate(
                        groupId, roomId, timeSlotId, examDate)
                : roomAssignmentRepository.existsByScheduleGroupIdAndRoomIdAndTimeSlotIdAndExamDateAndIdNot(
                        groupId, roomId, timeSlotId, examDate, existingId);
        if (duplicate) {
            throw new ConflictException(MessageFormat.format(Messages.ASSIGNMENT_DUPLICATE_ROOM_SLOT, assignment.getRoom().getName()));
        }

        // Universe = every assignment that day in ALL schedule groups (people and rooms are shared).
        SchedulingContext context = contextLoader.load(
                List.of(examDate),
                existing -> existingId != null && existingId.equals(existing.getId()));
        context.add(assignment);
        throwIfViolations(assignment, context);
    }

    /**
     * Validates inter-assignment constraints for a bulk operation using real time overlap.
     *
     * <p>The payload replaces one date+slot slice of one group. Everything else on those dates —
     * other slots, other schedule groups — stays in the database and is included in the check.
     */
    private void validateBulkAssignmentRules(List<RoomAssignment> assignments, UUID groupId) {
        Set<UUID> groupIds = assignments.stream()
                .map(assignment -> assignment.getScheduleGroup() != null ? assignment.getScheduleGroup().getId() : null)
                .collect(Collectors.toSet());
        if (groupIds.contains(null) || groupIds.size() != 1) {
            throw new ValidationException(Messages.ASSIGNMENT_BULK_MIXED_GROUPS);
        }

        Set<LocalDate> examDates = assignments.stream()
                .map(RoomAssignment::getExamDate)
                .collect(Collectors.toSet());
        Set<SlotDayKey> replacedSlots = assignments.stream()
                .map(SlotDayKey::from)
                .collect(Collectors.toSet());

        SchedulingContext context = contextLoader.load(examDates, existing ->
                existing.getScheduleGroup() != null
                        && groupId.equals(existing.getScheduleGroup().getId())
                        && replacedSlots.contains(SlotDayKey.from(existing)));
        context.addAll(assignments);

        for (RoomAssignment assignment : assignments) {
            throwIfViolations(assignment, context);
        }
    }

    private static void throwIfViolations(RoomAssignment assignment, SchedulingContext context) {
        List<String> violations = StaffConcurrencyRules.findViolations(assignment, context);
        if (!violations.isEmpty()) {
            throw new ConflictException(violations.getFirst());
        }
    }

    private record AssignmentCompositeKey(LocalDate examDate, UUID slotId, UUID roomId) {
        static AssignmentCompositeKey from(BulkRoomAssignmentRequest request) {
            return new AssignmentCompositeKey(request.examDate(), request.slotId(), request.roomId());
        }

        static AssignmentCompositeKey from(RoomAssignment assignment) {
            return new AssignmentCompositeKey(
                    assignment.getExamDate(),
                    assignment.getTimeSlot().getId(),
                    assignment.getRoom().getId()
            );
        }
    }

    private record SlotDayKey(LocalDate examDate, UUID slotId) {
        static SlotDayKey from(RoomAssignment assignment) {
            return new SlotDayKey(assignment.getExamDate(), assignment.getTimeSlot().getId());
        }
    }
}