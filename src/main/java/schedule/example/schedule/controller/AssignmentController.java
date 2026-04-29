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
import schedule.example.schedule.dto.assignment.BulkRoomAssignmentRequest;
import schedule.example.schedule.dto.assignment.RoomAssignmentRequest;
import schedule.example.schedule.dto.assignment.RoomAssignmentResponse;
import schedule.example.schedule.dto.common.PageResponse;
import schedule.example.schedule.service.AssignmentService;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/assignments")
public class AssignmentController {

	private static final Set<String> ALLOWED_SORTS = Set.of(
		"id",
		"examDate",
		"subjectName",
		"subjectCode",
		"locked",
		"source",
		"generationVersion",
		"timeSlot.startTime",
		"room.name"
	);

	private final AssignmentService assignmentService;
	private final PageRequestFactory pageRequestFactory;

	public AssignmentController(AssignmentService assignmentService, PageRequestFactory pageRequestFactory) {
		this.assignmentService = assignmentService;
		this.pageRequestFactory = pageRequestFactory;
	}

	@GetMapping
	public PageResponse<RoomAssignmentResponse> getAssignments(
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size,
		@RequestParam(defaultValue = "examDate") String sortBy,
		@RequestParam(defaultValue = "ASC") Sort.Direction direction,
		@RequestParam(required = false) UUID slotId,
		@RequestParam(required = false) UUID roomId,
		@RequestParam(required = false) Boolean locked,
		@RequestParam(required = false) LocalDate fromDate,
		@RequestParam(required = false) LocalDate toDate
	) {
		Pageable pageable = pageRequestFactory.create(page, size, sortBy, direction, ALLOWED_SORTS);
		return assignmentService.getAssignments(slotId, roomId, locked, fromDate, toDate, pageable);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public RoomAssignmentResponse createAssignment(@Valid @RequestBody RoomAssignmentRequest request) {
		return assignmentService.createAssignment(request);
	}

	@PostMapping("/bulk")
	@ResponseStatus(HttpStatus.CREATED)
	public List<RoomAssignmentResponse> saveAssignmentsBulk(
		@Valid @RequestBody List<@Valid BulkRoomAssignmentRequest> requests
	) {
		return assignmentService.saveAssignmentsBulk(requests);
	}

	@PutMapping("/{id}")
	public RoomAssignmentResponse updateAssignment(@PathVariable UUID id, @Valid @RequestBody RoomAssignmentRequest request) {
		return assignmentService.updateAssignment(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAssignment(@PathVariable UUID id) {
		assignmentService.deleteAssignment(id);
	}
}