package schedule.example.schedule.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.config.MessageResolver;
import schedule.example.schedule.dto.assignment.BulkRoomAssignmentRequest;
import schedule.example.schedule.dto.assignment.RoomAssignmentRequest;
import schedule.example.schedule.dto.assignment.RoomAssignmentResponse;
import schedule.example.schedule.dto.common.PageResponse;
import schedule.example.schedule.entity.InvigilatorAssignment;
import schedule.example.schedule.entity.Person;
import schedule.example.schedule.entity.Room;
import schedule.example.schedule.entity.RoomAssignment;
import schedule.example.schedule.entity.TimeSlot;
import schedule.example.schedule.entity.enums.AssignmentSource;
import schedule.example.schedule.entity.enums.PersonRole;
import schedule.example.schedule.entity.enums.WeekDay;
import schedule.example.schedule.exception.ConflictException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.mapper.RoomAssignmentMapper;
import schedule.example.schedule.repository.InvigilatorAssignmentRepository;
import schedule.example.schedule.repository.PersonRepository;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.repository.RoomRepository;
import schedule.example.schedule.repository.TimeSlotRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AssignmentService {

    private final RoomAssignmentRepository roomAssignmentRepository;
    private final InvigilatorAssignmentRepository invigilatorAssignmentRepository;
    private final RoomRepository roomRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final PersonRepository personRepository;
    private final RoomAssignmentMapper roomAssignmentMapper;
    private final MessageResolver messageResolver;

    public AssignmentService(
            RoomAssignmentRepository roomAssignmentRepository,
            InvigilatorAssignmentRepository invigilatorAssignmentRepository,
            RoomRepository roomRepository,
            TimeSlotRepository timeSlotRepository,
            PersonRepository personRepository,
            RoomAssignmentMapper roomAssignmentMapper,
            MessageResolver messageResolver
    ) {
        this.roomAssignmentRepository = roomAssignmentRepository;
        this.invigilatorAssignmentRepository = invigilatorAssignmentRepository;
        this.roomRepository = roomRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.personRepository = personRepository;
        this.roomAssignmentMapper = roomAssignmentMapper;
        this.messageResolver = messageResolver;
    }

    public PageResponse<RoomAssignmentResponse> getAssignments(
            UUID slotId, UUID roomId, Boolean locked,
            LocalDate fromDate, LocalDate toDate, Pageable pageable) {
        Page<UUID> assignmentIds = roomAssignmentRepository.findIdsByFilters(
                slotId, roomId, locked, fromDate, toDate, pageable);
        List<RoomAssignmentResponse> ordered = getOrderedAssignmentResponses(assignmentIds.getContent());
        return PageResponse.from(new PageImpl<>(ordered, pageable, assignmentIds.getTotalElements()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RoomAssignmentResponse createAssignment(RoomAssignmentRequest request) {
        RoomAssignment assignment = roomAssignmentMapper.toEntity(request);
        configureAssignmentRelations(assignment, request);
        validateAssignmentRules(assignment, null);

        RoomAssignment saved = roomAssignmentRepository.save(assignment);
        applyWorkloadDelta(Map.of(), countAssignmentOccurrences(saved));
        return roomAssignmentMapper.toResponse(getDetailedAssignment(saved.getId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RoomAssignmentResponse updateAssignment(UUID id, RoomAssignmentRequest request) {
        RoomAssignment assignment = getDetailedAssignment(id);
        Map<UUID, Integer> previousOccurrences = countAssignmentOccurrences(assignment);

        roomAssignmentMapper.updateEntity(request, assignment);
        configureAssignmentRelations(assignment, request);
        validateAssignmentRules(assignment, id);

        RoomAssignment saved = roomAssignmentRepository.save(assignment);
        applyWorkloadDelta(previousOccurrences, countAssignmentOccurrences(saved));
        return roomAssignmentMapper.toResponse(getDetailedAssignment(saved.getId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<RoomAssignmentResponse> saveAssignmentsBulk(List<BulkRoomAssignmentRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new ValidationException(messageResolver.get("assignment.bulk.empty"));
        }

        Map<UUID, Room> roomsById = loadRooms(requests);
        Map<UUID, TimeSlot> timeSlotsById = loadTimeSlots(requests);
        Map<UUID, Person> peopleById = loadPeople(requests);
        List<RoomAssignment> existingAssignments = loadExistingAssignments(requests);
        Map<AssignmentCompositeKey, RoomAssignment> existingByKey = existingAssignments.stream()
                .collect(Collectors.toMap(AssignmentCompositeKey::from, Function.identity()));

        validateRequestKeys(requests);

        Map<UUID, Integer> previousOccurrences = countAssignmentOccurrences(existingAssignments);
        List<RoomAssignment> preparedAssignments = new ArrayList<>(requests.size());

        for (BulkRoomAssignmentRequest request : requests) {
            AssignmentCompositeKey key = AssignmentCompositeKey.from(request);
            RoomAssignment assignment = existingByKey.getOrDefault(key, new RoomAssignment());
            configureBulkAssignmentRelations(assignment, request, roomsById, timeSlotsById, peopleById);
            preparedAssignments.add(assignment);
        }

        validateBulkAssignmentRules(preparedAssignments);

        List<RoomAssignment> toDelete = existingAssignments.stream()
                .filter(existing -> preparedAssignments.stream().noneMatch(prepared -> sameSlotRoom(existing, prepared)))
                .toList();

        if (!toDelete.isEmpty()) {
            roomAssignmentRepository.deleteAll(toDelete);
        }

        List<RoomAssignment> savedAssignments = roomAssignmentRepository.saveAll(preparedAssignments);
        List<UUID> savedIds = savedAssignments.stream()
                .map(RoomAssignment::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<UUID, Integer> nextOccurrences = countAssignmentOccurrences(savedAssignments);
        applyWorkloadDelta(previousOccurrences, nextOccurrences);

        return getOrderedAssignmentResponses(savedIds);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteAssignment(UUID id) {
        RoomAssignment assignment = getDetailedAssignment(id);
        Map<UUID, Integer> previousOccurrences = countAssignmentOccurrences(assignment);
        roomAssignmentRepository.delete(assignment);
        applyWorkloadDelta(previousOccurrences, Map.of());
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
                .orElseThrow(() -> new NotFoundException(messageResolver.get("assignment.not-found", id)));
    }

    private Map<UUID, Room> loadRooms(List<BulkRoomAssignmentRequest> requests) {
        Set<UUID> roomIds = requests.stream()
                .map(BulkRoomAssignmentRequest::roomId)
                .collect(Collectors.toSet());
        Map<UUID, Room> roomsById = roomRepository.findAllById(roomIds).stream()
                .collect(Collectors.toMap(Room::getId, Function.identity()));

        for (UUID roomId : roomIds) {
            if (!roomsById.containsKey(roomId)) {
                throw new NotFoundException(messageResolver.get("room.not-found", roomId));
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
                throw new NotFoundException(messageResolver.get("slot.not-found", slotId));
            }
            if (!timeSlot.isActive()) {
                throw new ValidationException(messageResolver.get("slot.inactive"));
            }
            if (!timeSlot.getEndTime().isAfter(timeSlot.getStartTime())) {
                throw new ValidationException(messageResolver.get("slot.invalid-range"));
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

    private List<RoomAssignment> loadExistingAssignments(List<BulkRoomAssignmentRequest> requests) {
        Set<LocalDate> examDates = requests.stream()
                .map(BulkRoomAssignmentRequest::examDate)
                .collect(Collectors.toSet());
        Set<UUID> slotIds = requests.stream()
                .map(BulkRoomAssignmentRequest::slotId)
                .collect(Collectors.toSet());

        return roomAssignmentRepository.findAllDetailedByExamDateInAndTimeSlotIdIn(examDates, slotIds);
    }

    private void validateRequestKeys(List<BulkRoomAssignmentRequest> requests) {
        Set<AssignmentCompositeKey> keys = new HashSet<>();
        for (BulkRoomAssignmentRequest request : requests) {
            AssignmentCompositeKey key = AssignmentCompositeKey.from(request);
            if (!keys.add(key)) {
                throw new ValidationException(messageResolver.get(
                        "assignment.bulk.duplicate-room-slot",
                        request.roomId(),
                        request.slotId(),
                        request.examDate()
                ));
            }
        }
    }

    private void configureAssignmentRelations(RoomAssignment assignment, RoomAssignmentRequest request) {
        Room room = roomRepository.findById(request.roomId())
                .orElseThrow(() -> new NotFoundException(messageResolver.get("room.not-found", request.roomId())));

        TimeSlot timeSlot = timeSlotRepository.findById(request.timeSlotId())
                .orElseThrow(() -> new NotFoundException(messageResolver.get("slot.not-found", request.timeSlotId())));

        if (!timeSlot.isActive()) {
            throw new ValidationException(messageResolver.get("slot.inactive"));
        }
        if (!timeSlot.getEndTime().isAfter(timeSlot.getStartTime())) {
            throw new ValidationException(messageResolver.get("slot.invalid-range"));
        }
        if (request.examDate() == null) {
            throw new ValidationException(messageResolver.get("assignment.slot-date-required"));
        }

        Person chiefInvigilator = resolveChiefInvigilator(request.chiefInvigilatorId());
        List<Person> invigilators = resolveInvigilators(request.invigilatorIds());

        assignment.setExamDate(request.examDate());
        assignment.setRoom(room);
        assignment.setTimeSlot(timeSlot);
        assignment.setChiefInvigilator(chiefInvigilator);
        assignment.setLocked(Boolean.TRUE.equals(request.locked()));
        assignment.setSource(request.source() != null ? request.source() : AssignmentSource.MANUAL);
        assignment.replaceInvigilatorAssignments(buildInvigilatorAssignments(invigilators, room.getMinInvigilators()));
    }

    private void configureBulkAssignmentRelations(
            RoomAssignment assignment,
            BulkRoomAssignmentRequest request,
            Map<UUID, Room> roomsById,
            Map<UUID, TimeSlot> timeSlotsById,
            Map<UUID, Person> peopleById
    ) {
        if (request.examDate() == null) {
            throw new ValidationException(messageResolver.get("assignment.slot-date-required"));
        }

        Room room = roomsById.get(request.roomId());
        if (room == null) {
            throw new NotFoundException(messageResolver.get("room.not-found", request.roomId()));
        }

        TimeSlot timeSlot = timeSlotsById.get(request.slotId());
        if (timeSlot == null) {
            throw new NotFoundException(messageResolver.get("slot.not-found", request.slotId()));
        }

        Person chiefInvigilator = resolveChiefInvigilator(request.chiefInvigilatorId(), peopleById);
        List<Person> invigilators = resolveInvigilators(request.invigilatorIds(), peopleById);

        assignment.setExamDate(request.examDate());
        assignment.setRoom(room);
        assignment.setTimeSlot(timeSlot);
        assignment.setChiefInvigilator(chiefInvigilator);
        assignment.setLocked(Boolean.TRUE.equals(request.isLocked()));
        assignment.setSource(AssignmentSource.GENERATED);
        assignment.replaceInvigilatorAssignments(buildInvigilatorAssignments(invigilators, room.getMinInvigilators()));
    }

    private Person resolveChiefInvigilator(UUID chiefInvigilatorId) {
        if (chiefInvigilatorId == null) return null;
        Person chief = personRepository.findById(chiefInvigilatorId)
                .orElseThrow(() -> new NotFoundException(messageResolver.get("person.not-found", chiefInvigilatorId)));
        if (chief.getRole() != PersonRole.CHIEF_INVIGILATOR) {
            throw new ValidationException(messageResolver.get("assignment.chief.role-invalid", chief.getName()));
        }
        return chief;
    }

    private Person resolveChiefInvigilator(UUID chiefInvigilatorId, Map<UUID, Person> peopleById) {
        if (chiefInvigilatorId == null) {
            return null;
        }

        Person chief = peopleById.get(chiefInvigilatorId);
        if (chief == null) {
            throw new NotFoundException(messageResolver.get("person.not-found", chiefInvigilatorId));
        }
        if (chief.getRole() != PersonRole.CHIEF_INVIGILATOR) {
            throw new ValidationException(messageResolver.get("assignment.chief.role-invalid", chief.getName()));
        }

        return chief;
    }

    private List<Person> resolveInvigilators(List<UUID> invigilatorIds) {
        List<UUID> nonNull = invigilatorIds.stream().filter(Objects::nonNull).toList();
        if (new HashSet<>(nonNull).size() != nonNull.size()) {
            throw new ValidationException(messageResolver.get("assignment.invigilator.duplicate"));
        }
        Map<UUID, Person> peopleById = personRepository.findAllById(nonNull).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        for (UUID id : nonNull) {
            if (!peopleById.containsKey(id)) {
                throw new NotFoundException(messageResolver.get("person.not-found", id));
            }
            Person p = peopleById.get(id);
            if (p.getRole() != PersonRole.INVIGILATOR) {
                throw new ValidationException(messageResolver.get("assignment.invigilator.role-invalid", p.getName()));
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
            throw new ValidationException(messageResolver.get("assignment.invigilator.duplicate"));
        }

        List<Person> ordered = new ArrayList<>(invigilatorIds.size());
        for (UUID invigilatorId : invigilatorIds) {
            if (invigilatorId == null) {
                ordered.add(null);
                continue;
            }

            Person person = peopleById.get(invigilatorId);
            if (person == null) {
                throw new NotFoundException(messageResolver.get("person.not-found", invigilatorId));
            }
            if (person.getRole() != PersonRole.INVIGILATOR) {
                throw new ValidationException(messageResolver.get("assignment.invigilator.role-invalid", person.getName()));
            }
            ordered.add(person);
        }

        return ordered;
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
        UUID roomId = assignment.getRoom().getId();
        UUID timeSlotId = assignment.getTimeSlot().getId();
        LocalDate examDate = assignment.getExamDate();

        boolean duplicate = existingId == null
                ? roomAssignmentRepository.existsByRoomIdAndTimeSlotIdAndExamDate(roomId, timeSlotId, examDate)
                : roomAssignmentRepository.existsByRoomIdAndTimeSlotIdAndExamDateAndIdNot(roomId, timeSlotId, examDate, existingId);
        if (duplicate) {
            throw new ConflictException(messageResolver.get("assignment.duplicate-room-slot", assignment.getRoom().getName()));
        }

        if (assignment.getChiefInvigilator() != null) {
            validateAvailability(assignment.getChiefInvigilator(), examDate, true);
            long chiefCount = existingId == null
                    ? roomAssignmentRepository.countByTimeSlotIdAndExamDateAndChiefInvigilatorId(timeSlotId, examDate, assignment.getChiefInvigilator().getId())
                    : roomAssignmentRepository.countByTimeSlotIdAndExamDateAndChiefInvigilatorIdAndIdNot(timeSlotId, examDate, assignment.getChiefInvigilator().getId(), existingId);
            if (chiefCount >= assignment.getChiefInvigilator().getMaxParallelRooms()) {
                throw new ConflictException(messageResolver.get("assignment.chief.limit", assignment.getChiefInvigilator().getName()));
            }
        }

        for (InvigilatorAssignment ia : assignment.getInvigilatorAssignments()) {
            if (ia.getInvigilator() == null) continue;
            validateAvailability(ia.getInvigilator(), examDate, false);
            long slotUsage = invigilatorAssignmentRepository.countSlotUsage(
                    timeSlotId, examDate, ia.getInvigilator().getId(), existingId);
            if (slotUsage > 0) {
                throw new ConflictException(messageResolver.get("assignment.invigilator.double-booked", ia.getInvigilator().getName()));
            }
        }
    }

    private void validateBulkAssignmentRules(List<RoomAssignment> assignments) {
        Map<SlotOccurrenceKey, Map<UUID, Integer>> chiefCounts = new HashMap<>();
        Map<SlotOccurrenceKey, Map<UUID, Integer>> invigilatorCounts = new HashMap<>();

        for (RoomAssignment assignment : assignments) {
            SlotOccurrenceKey key = SlotOccurrenceKey.from(assignment);
            if (assignment.getChiefInvigilator() != null) {
                validateAvailability(assignment.getChiefInvigilator(), assignment.getExamDate(), true);
                chiefCounts.computeIfAbsent(key, ignored -> new HashMap<>())
                        .merge(assignment.getChiefInvigilator().getId(), 1, Integer::sum);
            }

            for (InvigilatorAssignment invigilatorAssignment : assignment.getInvigilatorAssignments()) {
                if (invigilatorAssignment.getInvigilator() == null) {
                    continue;
                }

                validateAvailability(invigilatorAssignment.getInvigilator(), assignment.getExamDate(), false);
                invigilatorCounts.computeIfAbsent(key, ignored -> new HashMap<>())
                        .merge(invigilatorAssignment.getInvigilator().getId(), 1, Integer::sum);
            }
        }

        chiefCounts.forEach((key, counts) -> counts.forEach((personId, count) -> {
            RoomAssignment sample = assignments.stream()
                    .filter(assignment -> assignment.getChiefInvigilator() != null
                            && assignment.getChiefInvigilator().getId().equals(personId)
                            && SlotOccurrenceKey.from(assignment).equals(key))
                    .findFirst()
                    .orElse(null);
            if (sample != null && count > sample.getChiefInvigilator().getMaxParallelRooms()) {
                throw new ConflictException(messageResolver.get("assignment.chief.limit", sample.getChiefInvigilator().getName()));
            }
        }));

        invigilatorCounts.forEach((key, counts) -> counts.forEach((personId, count) -> {
            if (count <= 1) {
                return;
            }

            RoomAssignment sample = assignments.stream()
                    .filter(assignment -> SlotOccurrenceKey.from(assignment).equals(key)
                            && assignment.getInvigilatorAssignments().stream().anyMatch(invigilatorAssignment ->
                                    invigilatorAssignment.getInvigilator() != null
                                            && invigilatorAssignment.getInvigilator().getId().equals(personId)))
                    .findFirst()
                    .orElse(null);
            if (sample != null) {
                Person person = sample.getInvigilatorAssignments().stream()
                        .map(InvigilatorAssignment::getInvigilator)
                        .filter(Objects::nonNull)
                        .filter(candidate -> candidate.getId().equals(personId))
                        .findFirst()
                        .orElse(null);
                if (person != null) {
                    throw new ConflictException(messageResolver.get("assignment.invigilator.double-booked", person.getName()));
                }
            }
        }));
    }

    private void validateAvailability(Person person, LocalDate examDate, boolean isChief) {
        WeekDay required = WeekDay.from(examDate.getDayOfWeek());
        if (person.getAvailableDays().contains(required)) return;
        String key = isChief ? "assignment.chief.unavailable" : "assignment.invigilator.unavailable";
        throw new ConflictException(messageResolver.get(key, person.getName(), required.getValue(), examDate));
    }

    private Map<UUID, Integer> countAssignmentOccurrences(RoomAssignment assignment) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (assignment.getChiefInvigilator() != null) {
            counts.merge(assignment.getChiefInvigilator().getId(), 1, Integer::sum);
        }
        for (InvigilatorAssignment ia : assignment.getInvigilatorAssignments()) {
            if (ia.getInvigilator() != null) {
                counts.merge(ia.getInvigilator().getId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private Map<UUID, Integer> countAssignmentOccurrences(Collection<RoomAssignment> assignments) {
        Map<UUID, Integer> counts = new HashMap<>();
        assignments.stream()
                .sorted(Comparator.comparing(RoomAssignment::getExamDate))
                .forEach(assignment -> countAssignmentOccurrences(assignment)
                        .forEach((personId, value) -> counts.merge(personId, value, Integer::sum)));
        return counts;
    }

    private boolean sameSlotRoom(RoomAssignment left, RoomAssignment right) {
        return Objects.equals(left.getExamDate(), right.getExamDate())
                && Objects.equals(left.getTimeSlot().getId(), right.getTimeSlot().getId())
                && Objects.equals(left.getRoom().getId(), right.getRoom().getId());
    }

    private void applyWorkloadDelta(Map<UUID, Integer> before, Map<UUID, Integer> after) {
        Set<UUID> personIds = new HashSet<>(before.keySet());
        personIds.addAll(after.keySet());
        for (UUID personId : personIds) {
            int delta = after.getOrDefault(personId, 0) - before.getOrDefault(personId, 0);
            if (delta != 0) personRepository.adjustTotalAssignments(personId, delta);
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

    private record SlotOccurrenceKey(LocalDate examDate, UUID slotId) {
        static SlotOccurrenceKey from(RoomAssignment assignment) {
            return new SlotOccurrenceKey(assignment.getExamDate(), assignment.getTimeSlot().getId());
        }
    }
}