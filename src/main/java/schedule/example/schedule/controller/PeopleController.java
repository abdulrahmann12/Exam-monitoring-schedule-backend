package schedule.example.schedule.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import schedule.example.schedule.config.PageRequestFactory;
import schedule.example.schedule.dto.common.PageResponse;
import schedule.example.schedule.dto.person.PersonRequest;
import schedule.example.schedule.dto.person.PersonResponse;
import schedule.example.schedule.entity.enums.PersonRole;
import schedule.example.schedule.dto.unavailability.UnavailabilityRequest;
import schedule.example.schedule.dto.unavailability.UnavailabilityResponse;
import schedule.example.schedule.service.PeopleService;
import schedule.example.schedule.service.UnavailabilityService;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/people")
public class PeopleController {

	private static final Set<String> ALLOWED_SORTS = Set.of("id", "name", "department", "role", "totalAssignments");

	private final PeopleService peopleService;
	private final UnavailabilityService unavailabilityService;
	private final PageRequestFactory pageRequestFactory;

	public PeopleController(
		PeopleService peopleService,
		UnavailabilityService unavailabilityService,
		PageRequestFactory pageRequestFactory
	) {
		this.peopleService = peopleService;
		this.unavailabilityService = unavailabilityService;
		this.pageRequestFactory = pageRequestFactory;
	}

	@GetMapping
	public PageResponse<PersonResponse> getPeople(
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size,
		@RequestParam(defaultValue = "name") String sortBy,
		@RequestParam(defaultValue = "ASC") Sort.Direction direction,
		@RequestParam(required = false) PersonRole role,
		@RequestParam(required = false) String department,
		@RequestParam(required = false) String name,
		@RequestParam(required = false) UUID scheduleGroupId
	) {
		Pageable pageable = pageRequestFactory.create(page, size, sortBy, direction, ALLOWED_SORTS);
		return peopleService.getPeople(role, department, name, scheduleGroupId, pageable);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PersonResponse createPerson(@Valid @RequestBody PersonRequest request) {
		return peopleService.createPerson(request);
	}

	@PutMapping("/{id}")
	public PersonResponse updatePerson(@PathVariable UUID id, @Valid @RequestBody PersonRequest request) {
		return peopleService.updatePerson(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deletePerson(@PathVariable UUID id) {
		peopleService.deletePerson(id);
	}

	// ── Date-specific leave ────────────────────────────────────────────────

	@GetMapping("/{id}/unavailability")
	public List<UnavailabilityResponse> listUnavailability(@PathVariable UUID id) {
		return unavailabilityService.list(id);
	}

	@PostMapping("/{id}/unavailability")
	@ResponseStatus(HttpStatus.CREATED)
	public UnavailabilityResponse addUnavailability(@PathVariable UUID id, @Valid @RequestBody UnavailabilityRequest request) {
		return unavailabilityService.create(id, request);
	}

	@DeleteMapping("/{id}/unavailability/{unavailabilityId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteUnavailability(@PathVariable UUID id, @PathVariable UUID unavailabilityId) {
		unavailabilityService.delete(id, unavailabilityId);
	}
}
