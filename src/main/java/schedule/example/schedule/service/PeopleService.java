package schedule.example.schedule.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.config.MessageResolver;
import schedule.example.schedule.dto.common.PageResponse;
import schedule.example.schedule.dto.person.PersonRequest;
import schedule.example.schedule.dto.person.PersonResponse;
import schedule.example.schedule.entity.Person;
import schedule.example.schedule.entity.RoomAssignment;
import schedule.example.schedule.entity.enums.PersonRole;
import schedule.example.schedule.entity.enums.WeekDay;
import schedule.example.schedule.exception.ConflictException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.mapper.PersonMapper;
import schedule.example.schedule.repository.InvigilatorAssignmentRepository;
import schedule.example.schedule.repository.PersonRepository;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.util.NameNormalizationUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PeopleService {

	private final PersonRepository personRepository;
	private final RoomAssignmentRepository roomAssignmentRepository;
	private final InvigilatorAssignmentRepository invigilatorAssignmentRepository;
	private final PersonMapper personMapper;
	private final MessageResolver messageResolver;
	private final NormalizedNameMaintenanceService normalizedNameMaintenanceService;

	public PeopleService(
		PersonRepository personRepository,
		RoomAssignmentRepository roomAssignmentRepository,
		InvigilatorAssignmentRepository invigilatorAssignmentRepository,
		PersonMapper personMapper,
		MessageResolver messageResolver,
		NormalizedNameMaintenanceService normalizedNameMaintenanceService
	) {
		this.personRepository = personRepository;
		this.roomAssignmentRepository = roomAssignmentRepository;
		this.invigilatorAssignmentRepository = invigilatorAssignmentRepository;
		this.personMapper = personMapper;
		this.messageResolver = messageResolver;
		this.normalizedNameMaintenanceService = normalizedNameMaintenanceService;
	}

	public PageResponse<PersonResponse> getPeople(PersonRole role, String department, String name, Pageable pageable) {
		Page<PersonResponse> page = personRepository.search(role, department, name, pageable)
			.map(personMapper::toResponse);
		return PageResponse.from(page);
	}

	@Transactional
	public PersonResponse createPerson(PersonRequest request) {
		normalizedNameMaintenanceService.synchronizePeople();
		validateUniqueName(request.name(), null);
		Person person = personMapper.toEntity(request);
		person.setTotalAssignments(0);
		return personMapper.toResponse(personRepository.save(person));
	}

	@Transactional
	public PersonResponse updatePerson(UUID id, PersonRequest request) {
		normalizedNameMaintenanceService.synchronizePeople();
		Person person = getPersonEntity(id);
		validateUniqueName(request.name(), id);
		validateRoleChange(person, request.role());
		validateAvailabilityChange(person.getId(), request.availableDays());
		personMapper.updateEntity(request, person);
		return personMapper.toResponse(personRepository.save(person));
	}

	@Transactional
	public void deletePerson(UUID id) {
		Person person = getPersonEntity(id);

		if (roomAssignmentRepository.existsByChiefInvigilatorId(id) || invigilatorAssignmentRepository.existsByInvigilatorId(id)) {
			throw new ConflictException(messageResolver.get("person.delete.in-use", person.getName()));
		}

		personRepository.delete(person);
	}

	private Person getPersonEntity(UUID id) {
		return personRepository.findById(id)
			.orElseThrow(() -> new NotFoundException(messageResolver.get("person.not-found", id)));
	}

	private void validateUniqueName(String personName, UUID existingId) {
		String normalizedName = NameNormalizationUtil.normalizeForComparison(personName);
		personRepository.findByNormalizedName(normalizedName)
			.filter(existing -> existingId == null || !existing.getId().equals(existingId))
			.ifPresent(existing -> {
				throw new ConflictException(messageResolver.get("person.name.exists", personName));
			});
	}

	private void validateRoleChange(Person person, PersonRole newRole) {
		if (person.getRole() == newRole) {
			return;
		}

		if (roomAssignmentRepository.existsByChiefInvigilatorId(person.getId()) && newRole != PersonRole.CHIEF_INVIGILATOR) {
			throw new ConflictException(messageResolver.get("person.role-change.chief-in-use", person.getName()));
		}

		if (invigilatorAssignmentRepository.existsByInvigilatorId(person.getId()) && newRole != PersonRole.INVIGILATOR) {
			throw new ConflictException(messageResolver.get("person.role-change.invigilator-in-use", person.getName()));
		}
	}

	private void validateAvailabilityChange(UUID personId, Set<WeekDay> availableDays) {
		List<RoomAssignment> assignments = new ArrayList<>();
		assignments.addAll(roomAssignmentRepository.findAllChiefAssignmentsByPersonId(personId));
		assignments.addAll(roomAssignmentRepository.findAllInvigilatorAssignmentsByPersonId(personId));

		for (RoomAssignment assignment : assignments) {
			// examDate now carries the concrete date; TimeSlot is a dateless template
			WeekDay assignmentDay = WeekDay.from(assignment.getExamDate().getDayOfWeek());
			if (!availableDays.contains(assignmentDay)) {
				throw new ConflictException(messageResolver.get(
					"person.availability.conflict",
					assignmentDay.getValue(),
					assignment.getExamDate()
				));
			}
		}
	}
}