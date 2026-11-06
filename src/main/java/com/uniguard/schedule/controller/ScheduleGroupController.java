package com.uniguard.schedule.controller;

import java.util.Set;
import java.util.UUID;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import com.uniguard.schedule.config.PageRequestFactory;
import com.uniguard.schedule.dto.bulk.ScheduleBulkUploadResultDTO;
import com.uniguard.schedule.dto.common.PageResponse;
import com.uniguard.schedule.dto.schedulegroup.ScheduleGroupRequest;
import com.uniguard.schedule.dto.schedulegroup.ScheduleGroupResponse;
import com.uniguard.schedule.dto.schedulegroup.ScheduleNotifyResponse;
import com.uniguard.schedule.service.ScheduleBulkUploadService;
import com.uniguard.schedule.service.ScheduleGroupService;
import com.uniguard.schedule.service.ScheduleNotificationService;

@RestController
@RequestMapping("/api/schedule-groups")
public class ScheduleGroupController {

	private static final Set<String> ALLOWED_SORTS = Set.of("id", "name", "active", "createdAt", "updatedAt");
	private static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	private final ScheduleGroupService scheduleGroupService;
	private final ScheduleNotificationService scheduleNotificationService;
	private final ScheduleBulkUploadService scheduleBulkUploadService;
	private final PageRequestFactory pageRequestFactory;

	public ScheduleGroupController(
		ScheduleGroupService scheduleGroupService,
		ScheduleNotificationService scheduleNotificationService,
		ScheduleBulkUploadService scheduleBulkUploadService,
		PageRequestFactory pageRequestFactory
	) {
		this.scheduleGroupService = scheduleGroupService;
		this.scheduleNotificationService = scheduleNotificationService;
		this.scheduleBulkUploadService = scheduleBulkUploadService;
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
	public ScheduleNotifyResponse notifyAssignedStaff(
		@PathVariable UUID id,
		@org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean resendAll
	) {
		return scheduleNotificationService.notifyAssignedStaff(id, resendAll);
	}

	@GetMapping("/{id}/bulk-template")
	public ResponseEntity<ByteArrayResource> downloadBulkTemplate(@PathVariable UUID id) {
		byte[] templateBytes = scheduleBulkUploadService.createBulkTemplate(id);

		ByteArrayResource resource = new ByteArrayResource(templateBytes);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE));
		headers.setContentLength(templateBytes.length);
		headers.setContentDisposition(ContentDisposition.attachment().filename("schedule-bulk-upload-template.xlsx").build());

		return ResponseEntity.ok()
			.headers(headers)
			.body(resource);
	}

	@PostMapping(value = "/{id}/bulk-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ScheduleBulkUploadResultDTO uploadBulkSchedule(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
		return scheduleBulkUploadService.uploadBulkSchedule(id, file);
	}
}
