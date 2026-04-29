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
import schedule.example.schedule.service.PeopleService;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/people")
public class PeopleController {

	private static final Set<String> ALLOWED_SORTS = Set.of("id", "name", "department", "role", "totalAssignments");

	private final PeopleService peopleService;
	private final PageRequestFactory pageRequestFactory;

	public PeopleController(PeopleService peopleService, PageRequestFactory pageRequestFactory) {
		this.peopleService = peopleService;
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
		@RequestParam(required = false) String name
	) {
		Pageable pageable = pageRequestFactory.create(page, size, sortBy, direction, ALLOWED_SORTS);
		return peopleService.getPeople(role, department, name, pageable);
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
}