package com.uniguard.schedule.controller;

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

import com.uniguard.schedule.config.PageRequestFactory;
import com.uniguard.schedule.dto.assignment.DeleteAllAssignmentsResponse;
import com.uniguard.schedule.dto.assignment.BulkRoomAssignmentRequest;
import com.uniguard.schedule.dto.assignment.GeneratePreviewRequest;
import com.uniguard.schedule.dto.assignment.GeneratePreviewResponse;
import com.uniguard.schedule.dto.assignment.RoomAssignmentRequest;
import com.uniguard.schedule.dto.assignment.RoomAssignmentResponse;
import com.uniguard.schedule.dto.common.PageResponse;
import com.uniguard.schedule.service.AssignmentService;

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
		@RequestParam(required = false) UUID scheduleGroupId,
		@RequestParam(required = false) UUID slotId,
		@RequestParam(required = false) UUID roomId,
		@RequestParam(required = false) Boolean locked,
		@RequestParam(required = false) LocalDate fromDate,
		@RequestParam(required = false) LocalDate toDate
	) {
		Pageable pageable = pageRequestFactory.create(page, size, sortBy, direction, ALLOWED_SORTS);
		return assignmentService.getAssignments(scheduleGroupId, slotId, roomId, locked, fromDate, toDate, pageable);
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

	/**
	 * Generates staff for one date + time slot using the server's scheduling engine and returns
	 * the proposal <em>without saving it</em>. The client reviews/edits it, then saves via {@code /bulk}.
	 */
	@PostMapping("/generate-preview")
	public GeneratePreviewResponse generatePreview(@Valid @RequestBody GeneratePreviewRequest request) {
		return assignmentService.generatePreview(request);
	}

	@PutMapping("/{id}")
	public RoomAssignmentResponse updateAssignment(@PathVariable UUID id, @Valid @RequestBody RoomAssignmentRequest request) {
		return assignmentService.updateAssignment(id, request);
	}

	/** Deletes every assignment of one exam period, all at once or not at all. */
	@DeleteMapping
	public DeleteAllAssignmentsResponse deleteAllAssignments(@RequestParam UUID scheduleGroupId) {
		return assignmentService.deleteAllInGroup(scheduleGroupId);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAssignment(@PathVariable UUID id) {
		assignmentService.deleteAssignment(id);
	}
}