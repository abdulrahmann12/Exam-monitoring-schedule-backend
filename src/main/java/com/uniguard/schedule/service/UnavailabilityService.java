package com.uniguard.schedule.service;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.dto.unavailability.UnavailabilityRequest;
import com.uniguard.schedule.dto.unavailability.UnavailabilityResponse;
import com.uniguard.schedule.entity.Person;
import com.uniguard.schedule.entity.PersonUnavailability;
import com.uniguard.schedule.exception.ConflictException;
import com.uniguard.schedule.exception.NotFoundException;
import com.uniguard.schedule.exception.ValidationException;
import com.uniguard.schedule.repository.PersonRepository;
import com.uniguard.schedule.repository.PersonUnavailabilityRepository;
import com.uniguard.schedule.repository.RoomAssignmentRepository;

import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Date-specific leave for staff. Assignments are blocked on those dates by {@link StaffConcurrencyRules}. */
@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UnavailabilityService {

	private static final int MAX_LISTED_DATES = 5;

	private final PersonRepository personRepository;
	private final PersonUnavailabilityRepository unavailabilityRepository;
	private final RoomAssignmentRepository roomAssignmentRepository;
	private final ScheduleWriteLock scheduleWriteLock;
	private final DemoModeService demoModeService;

	public List<UnavailabilityResponse> list(UUID personId) {
		requirePerson(personId);
		return unavailabilityRepository.findAllByPersonIdOrderByStartDateAsc(personId).stream()
			.map(UnavailabilityService::toResponse)
			.toList();
	}

	/**
	 * Adds a leave period. Rejected when the person already has duties inside it — the admin must
	 * reassign those first, so the saved schedule never silently contradicts someone's leave.
	 */
	@Transactional
	public UnavailabilityResponse create(UUID personId, @Valid UnavailabilityRequest request) {
		if (request.endDate().isBefore(request.startDate())) {
			throw new ValidationException(Messages.UNAVAILABILITY_INVALID_RANGE);
		}
		scheduleWriteLock.acquire();
		Person person = requirePerson(personId);

		List<LocalDate> clashing = roomAssignmentRepository.findDutyDatesForPersonBetween(
			personId, request.startDate(), request.endDate());
		if (!clashing.isEmpty()) {
			String dates = clashing.stream().limit(MAX_LISTED_DATES).map(LocalDate::toString).collect(Collectors.joining(", "))
				+ (clashing.size() > MAX_LISTED_DATES ? " …" : "");
			throw new ConflictException(MessageFormat.format(Messages.UNAVAILABILITY_CONFLICT, person.getName(), dates));
		}

		PersonUnavailability saved = unavailabilityRepository.save(PersonUnavailability.builder()
			.person(person)
			.startDate(request.startDate())
			.endDate(request.endDate())
			.reason(request.reason() == null || request.reason().isBlank() ? null : request.reason().trim())
			.build());
		return toResponse(saved);
	}

	@Transactional
	public void delete(UUID personId, UUID unavailabilityId) {
		demoModeService.rejectIfDemoUser("delete-unavailability");
		PersonUnavailability leave = unavailabilityRepository.findById(unavailabilityId)
			.filter(u -> u.getPerson().getId().equals(personId))
			.orElseThrow(() -> new NotFoundException(MessageFormat.format(Messages.UNAVAILABILITY_NOT_FOUND, unavailabilityId)));
		unavailabilityRepository.delete(leave);
	}

	private Person requirePerson(UUID personId) {
		return personRepository.findById(personId)
			.orElseThrow(() -> new NotFoundException(MessageFormat.format(Messages.PERSON_NOT_FOUND, personId)));
	}

	private static UnavailabilityResponse toResponse(PersonUnavailability u) {
		return new UnavailabilityResponse(u.getId(), u.getPerson().getId(), u.getStartDate(), u.getEndDate(), u.getReason(), u.getCreatedAt());
	}
}
