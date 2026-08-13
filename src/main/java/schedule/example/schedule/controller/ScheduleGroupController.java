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
import schedule.example.schedule.dto.schedulegroup.ScheduleGroupRequest;
import schedule.example.schedule.dto.schedulegroup.ScheduleGroupResponse;
import schedule.example.schedule.dto.schedulegroup.ScheduleNotifyResponse;
import schedule.example.schedule.service.ScheduleGroupService;
import schedule.example.schedule.service.ScheduleNotificationService;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/schedule-groups")
public class ScheduleGroupController {

	private static final Set<String> ALLOWED_SORTS = Set.of("id", "name", "active", "createdAt", "updatedAt");

	private final ScheduleGroupService scheduleGroupService;
	private final ScheduleNotificationService scheduleNotificationService;
	private final PageRequestFactory pageRequestFactory;

	public ScheduleGroupController(
		ScheduleGroupService scheduleGroupService,
		ScheduleNotificationService scheduleNotificationService,
		PageRequestFactory pageRequestFactory
	) {
		this.scheduleGroupService = scheduleGroupService;
		this.scheduleNotificationService = scheduleNotificationService;
		this.pageRequestFactory = pageRequestFactory;
	}

	@GetMapping
	public PageResponse<ScheduleGroupResponse> getScheduleGroups(
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size,
		@RequestParam(defaultValue = "createdAt") String sortBy,
		@RequestParam(defaultValue = "ASC") Sort.Direction direction,
		@RequestParam(required = false) String name,
		@RequestParam(required = false) Boolean activeOnly
	) {
		Pageable pageable = pageRequestFactory.create(page, size, sortBy, direction, ALLOWED_SORTS);
		return scheduleGroupService.getScheduleGroups(name, activeOnly, pageable);
	}

	@GetMapping("/{id}")
	public ScheduleGroupResponse getScheduleGroup(@PathVariable UUID id) {
		return scheduleGroupService.getScheduleGroup(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ScheduleGroupResponse createScheduleGroup(@Valid @RequestBody ScheduleGroupRequest request) {
		return scheduleGroupService.createScheduleGroup(request);
	}

	@PutMapping("/{id}")
	public ScheduleGroupResponse updateScheduleGroup(@PathVariable UUID id, @Valid @RequestBody ScheduleGroupRequest request) {
		return scheduleGroupService.updateScheduleGroup(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteScheduleGroup(@PathVariable UUID id) {
		scheduleGroupService.deleteScheduleGroup(id);
	}

	@PostMapping("/{id}/notify")
	public ScheduleNotifyResponse notifyAssignedStaff(@PathVariable UUID id) {
		return scheduleNotificationService.notifyAssignedStaff(id);
	}
}
