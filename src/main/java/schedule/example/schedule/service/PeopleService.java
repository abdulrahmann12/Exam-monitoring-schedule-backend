package schedule.example.schedule.service;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.common.PageResponse;
import schedule.example.schedule.dto.person.PersonRequest;
import schedule.example.schedule.dto.person.PersonResponse;
import schedule.example.schedule.entity.Person;
import schedule.example.schedule.entity.enums.PersonRole;
import schedule.example.schedule.entity.enums.WeekDay;
import schedule.example.schedule.exception.ConflictException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.mapper.PersonMapper;
import schedule.example.schedule.repository.InvigilatorAssignmentRepository;
import schedule.example.schedule.repository.PersonRepository;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.util.NameNormalizationUtil;

import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PeopleService {

	private final PersonRepository personRepository;
	private final RoomAssignmentRepository roomAssignmentRepository;
	private final InvigilatorAssignmentRepository invigilatorAssignmentRepository;
	private final PersonMapper personMapper;
	private final NormalizedNameMaintenanceService normalizedNameMaintenanceService;
	private final DemoModeService demoModeService;


	/**
	 * Returns a paginated list of people matching the given filters.
	 *
	 * <p><strong>Search optimization:</strong> Both {@code name} and {@code department} are
	 * converted to trailing-wildcard patterns before being passed to the repository:
	 * <ul>
	 *   <li>{@code name} → normalized (lowercase + trim) + {@code %} suffix, matched against
	 *       the indexed {@code normalized_name} column. Uses the B-tree index for a fast prefix
	 *       range scan — no full table scan.</li>
	 *   <li>{@code department} → lowercase + {@code %} suffix, matched against the indexed
	 *       {@code department} column. Trailing-only wildcard allows MySQL to use
	 *       {@code idx_person_department}.</li>
	 * </ul>
	 * Note: search behavior changed from substring ({@code '%term%'}) to prefix ({@code 'term%'}).
	 * This is a UX tradeoff to eliminate full table scans at large dataset sizes.
	 */
	public PageResponse<PersonResponse> getPeople(
		PersonRole role,
		String department,
		String name,
		UUID scheduleGroupId,
		Pageable pageable
	) {
		String namePattern = (name != null && !name.isBlank())
			? NameNormalizationUtil.normalizeForComparison(name) + "%"
			: null;
		String deptPattern = (department != null && !department.isBlank())
			? department.toLowerCase(Locale.ROOT) + "%"
			: null;

		Map<UUID, Integer> groupWorkload = scheduleGroupId == null
			? Map.of()
			: countAssignmentsByScheduleGroup(scheduleGroupId);

		if (scheduleGroupId != null && isSortedByTotalAssignments(pageable)) {
			List<PersonResponse> all = personRepository.search(role, deptPattern, namePattern, Pageable.unpaged())
				.stream()
				.map(personMapper::toResponse)
				.map(person -> person.withTotalAssignments(groupWorkload.getOrDefault(person.id(), 0)))
				.sorted(totalAssignmentsComparator(pageable.getSort()))
				.toList();
			int start = (int) pageable.getOffset();
			int end = Math.min(start + pageable.getPageSize(), all.size());
			List<PersonResponse> slice = start >= all.size() ? List.of() : all.subList(start, end);
			return PageResponse.from(new PageImpl<>(slice, pageable, all.size()));
		}

		Page<PersonResponse> page = personRepository.search(role, deptPattern, namePattern, pageable)
			.map(personMapper::toResponse)
			.map(person -> scheduleGroupId == null
				? person
				: person.withTotalAssignments(groupWorkload.getOrDefault(person.id(), 0)));
		return PageResponse.from(page);
	}

	public Map<UUID, Integer> countAssignmentsByScheduleGroup(UUID groupId) {
		Map<UUID, Integer> counts = new HashMap<>();
		for (var row : roomAssignmentRepository.countChiefAssignmentsByScheduleGroupId(groupId)) {
			counts.merge(row.getPersonId(), (int) row.getAssignmentCount(), Integer::sum);
		}
		for (var row : invigilatorAssignmentRepository.countInvigilatorAssignmentsByScheduleGroupId(groupId)) {
			counts.merge(row.getPersonId(), (int) row.getAssignmentCount(), Integer::sum);
		}
		return counts;
	}

	private static boolean isSortedByTotalAssignments(Pageable pageable) {
		return pageable.getSort().getOrderFor("totalAssignments") != null;
	}

	private static Comparator<PersonResponse> totalAssignmentsComparator(Sort sort) {
		Sort.Order order = sort.getOrderFor("totalAssignments");
		Comparator<PersonResponse> comparator = Comparator
			.comparingInt(PersonResponse::totalAssignments)
			.thenComparing(PersonResponse::name, Comparator.nullsLast(String::compareToIgnoreCase));
		return order != null && order.isDescending() ? comparator.reversed() : comparator;
	}

	@Transactional
	public PersonResponse createPerson(@Valid PersonRequest request) {
		normalizedNameMaintenanceService.synchronizePeople();
		validateUniqueName(request.name(), null);
		validateUniqueEmail(request.email(), null);
		Person person = personMapper.toEntity(request);
		person.setTotalAssignments(0);
		return personMapper.toResponse(personRepository.save(person));
	}

	@Transactional
	public PersonResponse updatePerson(UUID id, @Valid PersonRequest request) {
		normalizedNameMaintenanceService.synchronizePeople();
		Person person = getPersonEntity(id);
		validateUniqueName(request.name(), id);
		validateUniqueEmail(request.email(), id);
		validateRoleChange(person, request.role());
		validateAvailabilityChange(person.getId(), request.availableDays());
		personMapper.updateEntity(request, person);
		return personMapper.toResponse(personRepository.save(person));
	}

	@Transactional
	public void deletePerson(UUID id) {
		demoModeService.rejectIfDemoUser("delete-person");
		Person person = getPersonEntity(id);

		if (roomAssignmentRepository.existsByChiefInvigilatorId(id) || invigilatorAssignmentRepository.existsByInvigilatorId(id)) {
			throw new ConflictException(MessageFormat.format(Messages.PERSON_DELETE_IN_USE, person.getName()));
		}

		personRepository.delete(person);
	}

	private Person getPersonEntity(UUID id) {
		return personRepository.findById(id)
			.orElseThrow(() -> new NotFoundException(MessageFormat.format(Messages.PERSON_NOT_FOUND, id)));
	}

	private void validateUniqueEmail(String email, UUID existingId) {
		if (email == null || email.isBlank()) {
			return;
		}
		personRepository.findByEmailIgnoreCase(email.trim())
			.filter(existing -> existingId == null || !existing.getId().equals(existingId))
			.ifPresent(existing -> {
				throw new ConflictException(MessageFormat.format(Messages.PERSON_EMAIL_EXISTS, email.trim()));
			});
	}

	private void validateUniqueName(String personName, UUID existingId) {
		String normalizedName = NameNormalizationUtil.normalizeForComparison(personName);
		personRepository.findByNormalizedName(normalizedName)
			.filter(existing -> existingId == null || !existing.getId().equals(existingId))
			.ifPresent(existing -> {
				throw new ConflictException(MessageFormat.format(Messages.PERSON_NAME_EXISTS, personName));
			});
	}

	private void validateRoleChange(Person person, PersonRole newRole) {
		if (person.getRole() == newRole) {
			return;
		}

		if (roomAssignmentRepository.existsByChiefInvigilatorId(person.getId()) && newRole != PersonRole.CHIEF_INVIGILATOR) {
			throw new ConflictException(MessageFormat.format(Messages.PERSON_ROLE_CHANGE_CHIEF_IN_USE, person.getName()));
		}

		if (invigilatorAssignmentRepository.existsByInvigilatorId(person.getId()) && newRole != PersonRole.INVIGILATOR) {
			throw new ConflictException(MessageFormat.format(Messages.PERSON_ROLE_CHANGE_INVIGILATOR_IN_USE, person.getName()));
		}
	}

	/**
	 * Validates that the updated available days do not conflict with any FUTURE assignments.
	 *
	 * <p><strong>Performance fix — scalar date projection:</strong> The previous implementation
	 * loaded full {@link schedule.example.schedule.entity.RoomAssignment} entities (with
	 * {@code timeSlot} join-fetched) via two queries. The caller only ever accessed
	 * {@code assignment.getExamDate()} — all other fields (room, subject, source, etc.)
	 * were transferred from the DB and immediately discarded.
	 *
	 * <p>This version uses two scalar {@code SELECT ra.examDate} queries returning only
	 * {@code List<LocalDate>}. This eliminates the unnecessary JOIN to {@code time_slots} and
	 * reduces data transferred from the DB significantly.
	 */
	private void validateAvailabilityChange(UUID personId, Set<WeekDay> availableDays) {
		LocalDate today = LocalDate.now();

		List<LocalDate> futureDates = new java.util.ArrayList<>();
		futureDates.addAll(roomAssignmentRepository.findFutureChiefExamDatesByPersonId(personId, today));
		futureDates.addAll(roomAssignmentRepository.findFutureInvigilatorExamDatesByPersonId(personId, today));

		for (LocalDate examDate : futureDates) {
			WeekDay assignmentDay = WeekDay.from(examDate.getDayOfWeek());
			if (!availableDays.contains(assignmentDay)) {
				throw new ConflictException(MessageFormat.format(
					Messages.PERSON_AVAILABILITY_CONFLICT,
					assignmentDay.getValue(),
					examDate
				));
			}
		}
	}
}