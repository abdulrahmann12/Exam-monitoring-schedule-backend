package schedule.example.schedule.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.config.Messages;
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

import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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

    public AssignmentService(
            RoomAssignmentRepository roomAssignmentRepository,
            InvigilatorAssignmentRepository invigilatorAssignmentRepository,
            RoomRepository roomRepository,
            TimeSlotRepository timeSlotRepository,
            PersonRepository personRepository,
            RoomAssignmentMapper roomAssignmentMapper
    ) {
        this.roomAssignmentRepository = roomAssignmentRepository;
        this.invigilatorAssignmentRepository = invigilatorAssignmentRepository;
        this.roomRepository = roomRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.personRepository = personRepository;
        this.roomAssignmentMapper = roomAssignmentMapper;
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

        // Map directly from the already-populated entity — no redundant EntityGraph re-fetch.
        return roomAssignmentMapper.toResponse(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RoomAssignmentResponse updateAssignment(UUID id, RoomAssignmentRequest request) {
        // Fetch once with full EntityGraph — all relations are in the session.
        RoomAssignment assignment = getDetailedAssignment(id);
        Map<UUID, Integer> previousOccurrences = countAssignmentOccurrences(assignment);

        roomAssignmentMapper.updateEntity(request, assignment);
        configureAssignmentRelations(assignment, request);
        validateAssignmentRules(assignment, id);

        roomAssignmentRepository.save(assignment);
        applyWorkloadDelta(previousOccurrences, countAssignmentOccurrences(assignment));

        // Relations are still in the session from the initial fetch — map directly, no re-fetch.
        return roomAssignmentMapper.toResponse(assignment);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<RoomAssignmentResponse> saveAssignmentsBulk(List<BulkRoomAssignmentRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new ValidationException(Messages.ASSIGNMENT_BULK_EMPTY);
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

        Set<AssignmentCompositeKey> preparedKeys = preparedAssignments.stream()
                .map(AssignmentCompositeKey::from)
                .collect(Collectors.toSet());

        List<RoomAssignment> toDelete = existingAssignments.stream()
                .filter(existing -> !preparedKeys.contains(AssignmentCompositeKey.from(existing)))
                .toList();

        if (!toDelete.isEmpty()) {
            roomAssignmentRepository.deleteAll(toDelete);
        }

        // saveAll() sends batched INSERTs/UPDATEs in groups of hibernate.jdbc.batch_size (50).
        List<RoomAssignment> savedAssignments = roomAssignmentRepository.saveAll(preparedAssignments);

        Map<UUID, Integer> nextOccurrences = countAssignmentOccurrences(savedAssignments);
        applyWorkloadDelta(previousOccurrences, nextOccurrences);

        // Map directly from the in-session entities — all relations already populated by
        // configureBulkAssignmentRelations(). Avoids the previous redundant EntityGraph re-fetch.
        return savedAssignments.stream()
                .filter(ra -> ra.getId() != null)
                .map(roomAssignmentMapper::toResponse)
                .toList();
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
        assignment.setChiefInvigilator(chiefInvigilator);
        assignment.setLocked(Boolean.TRUE.equals(request.isLocked()));
        assignment.setSource(AssignmentSource.GENERATED);
        assignment.replaceInvigilatorAssignments(buildInvigilatorAssignments(invigilators, room.getMinInvigilators()));
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
            throw new ConflictException(MessageFormat.format(Messages.ASSIGNMENT_DUPLICATE_ROOM_SLOT, assignment.getRoom().getName()));
        }

        if (assignment.getChiefInvigilator() != null) {
            validateAvailability(assignment.getChiefInvigilator(), examDate, true);
            long chiefCount = existingId == null
                    ? roomAssignmentRepository.countByTimeSlotIdAndExamDateAndChiefInvigilatorId(timeSlotId, examDate, assignment.getChiefInvigilator().getId())
                    : roomAssignmentRepository.countByTimeSlotIdAndExamDateAndChiefInvigilatorIdAndIdNot(timeSlotId, examDate, assignment.getChiefInvigilator().getId(), existingId);
            if (chiefCount >= assignment.getChiefInvigilator().getMaxParallelRooms()) {
                throw new ConflictException(MessageFormat.format(Messages.ASSIGNMENT_CHIEF_LIMIT, assignment.getChiefInvigilator().getName()));
            }
        }

        // Collect all non-null invigilators for a single batch double-booking check.
        List<InvigilatorAssignment> filledSlots = assignment.getInvigilatorAssignments().stream()
                .filter(ia -> ia.getInvigilator() != null)
                .toList();

        if (!filledSlots.isEmpty()) {
            // Availability check is in-memory — no DB calls.
            filledSlots.forEach(ia -> validateAvailability(ia.getInvigilator(), examDate, false));

            // Single IN-query replaces N individual COUNT queries (N = number of invigilators).
            List<UUID> invigilatorIds = filledSlots.stream()
                    .map(ia -> ia.getInvigilator().getId())
                    .toList();

            Set<UUID> doubleBooked = invigilatorAssignmentRepository.findDoubleBookedInvigilatorIds(
                    timeSlotId, examDate, invigilatorIds, existingId);

            if (!doubleBooked.isEmpty()) {
                InvigilatorAssignment culprit = filledSlots.stream()
                        .filter(ia -> doubleBooked.contains(ia.getInvigilator().getId()))
                        .findFirst()
                        .orElseThrow();
                throw new ConflictException(MessageFormat.format(
                        Messages.ASSIGNMENT_INVIGILATOR_DOUBLE_BOOKED, culprit.getInvigilator().getName()));
            }
        }
    }

    /**
     * Validates inter-assignment constraints for a bulk operation.
     *
     * <p><strong>Performance fix (O(n²) → O(n)):</strong>
     * The previous implementation searched through all assignments with a linear stream scan
     * for every (slot-key, personId) entry in the count maps — O(K × N) total.
     *
     * <p>This version builds lookup maps ({@code chiefSamples}, {@code invigilatorSamples})
     * in the initial loop — O(N) — so violation reporting is O(1) per entry.
     */
    private void validateBulkAssignmentRules(List<RoomAssignment> assignments) {
        // Occurrence counts per (slotKey, personId)
        Map<SlotOccurrenceKey, Map<UUID, Integer>> chiefCounts = new HashMap<>();
        Map<SlotOccurrenceKey, Map<UUID, Integer>> invigilatorCounts = new HashMap<>();

        // Sample assignment / person references for violation messages — built in same loop, O(1) lookup
        Map<SlotOccurrenceKey, Map<UUID, RoomAssignment>> chiefSamples = new HashMap<>();
        Map<SlotOccurrenceKey, Map<UUID, Person>> invigilatorSamples = new HashMap<>();

        for (RoomAssignment assignment : assignments) {
            SlotOccurrenceKey key = SlotOccurrenceKey.from(assignment);

            if (assignment.getChiefInvigilator() != null) {
                validateAvailability(assignment.getChiefInvigilator(), assignment.getExamDate(), true);
                UUID chiefId = assignment.getChiefInvigilator().getId();
                chiefCounts.computeIfAbsent(key, k -> new HashMap<>()).merge(chiefId, 1, Integer::sum);
                chiefSamples.computeIfAbsent(key, k -> new HashMap<>()).putIfAbsent(chiefId, assignment);
            }

            for (InvigilatorAssignment ia : assignment.getInvigilatorAssignments()) {
                if (ia.getInvigilator() == null) continue;
                validateAvailability(ia.getInvigilator(), assignment.getExamDate(), false);
                UUID invId = ia.getInvigilator().getId();
                invigilatorCounts.computeIfAbsent(key, k -> new HashMap<>()).merge(invId, 1, Integer::sum);
                invigilatorSamples.computeIfAbsent(key, k -> new HashMap<>()).putIfAbsent(invId, ia.getInvigilator());
            }
        }

        // Validate chief parallel-room limits — O(1) lookup per entry via chiefSamples
        chiefCounts.forEach((key, counts) -> counts.forEach((chiefId, count) -> {
            RoomAssignment sample = chiefSamples.get(key).get(chiefId);
            Person chief = sample.getChiefInvigilator();
            if (count > chief.getMaxParallelRooms()) {
                throw new ConflictException(
                    MessageFormat.format(Messages.ASSIGNMENT_CHIEF_LIMIT, chief.getName()));
            }
        }));

        // Validate invigilator double-booking — O(1) lookup per entry via invigilatorSamples
        invigilatorCounts.forEach((key, counts) -> counts.forEach((invId, count) -> {
            if (count <= 1) return;
            Person person = invigilatorSamples.get(key).get(invId);
            throw new ConflictException(
                MessageFormat.format(Messages.ASSIGNMENT_INVIGILATOR_DOUBLE_BOOKED, person.getName()));
        }));
    }

    private void validateAvailability(Person person, LocalDate examDate, boolean isChief) {
        WeekDay required = WeekDay.from(examDate.getDayOfWeek());
        if (person.getAvailableDays().contains(required)) return;
        String pattern = isChief ? Messages.ASSIGNMENT_CHIEF_UNAVAILABLE : Messages.ASSIGNMENT_INVIGILATOR_UNAVAILABLE;
        throw new ConflictException(MessageFormat.format(pattern, person.getName(), required.getValue(), examDate));
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

    /**
     * Counts total person occurrences across a collection of assignments.
     *
     * <p><strong>Fix:</strong> Removed the unnecessary {@code .sorted(Comparator.comparing(...))}
     * call from the previous implementation. Summing counts is commutative — ordering has no
     * effect on the result, and sorting added O(n log n) overhead for zero benefit.
     */
    private Map<UUID, Integer> countAssignmentOccurrences(Collection<RoomAssignment> assignments) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (RoomAssignment assignment : assignments) {
            countAssignmentOccurrences(assignment)
                .forEach((personId, value) -> counts.merge(personId, value, Integer::sum));
        }
        return counts;
    }

    /**
     * Updates {@code totalAssignments} for all affected persons using a single batch operation.
     *
     * <p><strong>Performance fix:</strong> the previous implementation called
     * {@code personRepository.adjustTotalAssignments(personId, delta)} once per person in a loop,
     * issuing N individual UPDATE round-trips. This version:
     * <ol>
     *   <li>Computes net deltas in memory (no DB call).</li>
     *   <li>Loads all affected persons in a single {@code SELECT ... IN} query.</li>
     *   <li>Updates {@code totalAssignments} in-memory for each person.</li>
     *   <li>Calls {@code saveAll()} which sends all UPDATEs as a single JDBC batch
     *       (requires {@code hibernate.jdbc.batch_size=50} + {@code rewriteBatchedStatements=true}
     *       in the JDBC URL — both configured in application.properties).</li>
     * </ol>
     * Result: N DB round-trips → 1 SELECT + 1 batched UPDATE round-trip.
     */
    private void applyWorkloadDelta(Map<UUID, Integer> before, Map<UUID, Integer> after) {
        Map<UUID, Integer> netDeltas = new HashMap<>();
        Set<UUID> allIds = new HashSet<>(before.keySet());
        allIds.addAll(after.keySet());

        for (UUID personId : allIds) {
            int delta = after.getOrDefault(personId, 0) - before.getOrDefault(personId, 0);
            if (delta != 0) netDeltas.put(personId, delta);
        }

        if (netDeltas.isEmpty()) return;

        List<Person> people = personRepository.findAllById(netDeltas.keySet());
        for (Person person : people) {
            Integer delta = netDeltas.get(person.getId());
            if (delta != null) {
                person.setTotalAssignments(person.getTotalAssignments() + delta);
            }
        }
        personRepository.saveAll(people);
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