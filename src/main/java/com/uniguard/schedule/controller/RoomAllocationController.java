package com.uniguard.schedule.controller;

import java.util.UUID;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.uniguard.schedule.dto.allocation.RoomAllocationPreviewDTO;
import com.uniguard.schedule.dto.allocation.RoomAllocationResults;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import com.uniguard.schedule.service.RoomAllocationService;

/** Room allocation: the system picks rooms for an uploaded exam list. */
@RestController
@RequestMapping("/api/room-allocation")
public class RoomAllocationController {

	private final RoomAllocationService roomAllocationService;

	public RoomAllocationController(RoomAllocationService roomAllocationService) {
		this.roomAllocationService = roomAllocationService;
	}

	/** Excel template; the Time Slot column is a dropdown of this university's time slots. */
	@GetMapping("/template")
	public ResponseEntity<ByteArrayResource> template() {
		byte[] bytes = roomAllocationService.createTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
		headers.setContentLength(bytes.length);
		headers.setContentDisposition(ContentDisposition.attachment().filename("exam-list-template.xlsx").build());
		return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(bytes));
	}

	/** Saves the proposed rooms (plans again from the same file). Staff are not assigned yet. */
	@PostMapping(value = "/save", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public RoomAllocationResults.Saved save(
		@RequestParam("scheduleGroupId") UUID scheduleGroupId,
		@RequestParam("file") MultipartFile file,
		@RequestParam(value = "dateOrder", required = false) String dateOrder
	) {
		return roomAllocationService.save(scheduleGroupId, file, dateOrder);
	}

	/** "Create schedule": assigns chiefs and invigilators to the saved exam rooms. */
	@PostMapping("/staff")
	public RoomAllocationResults.Staffed staff(@Valid @RequestBody RoomAllocationResults.StaffRequest request) {
		return roomAllocationService.staff(request);
	}

	/** Proposes rooms for every exam in the file. Saves nothing. */
	@PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public RoomAllocationPreviewDTO preview(
		@RequestParam("scheduleGroupId") UUID scheduleGroupId,
		@RequestParam("file") MultipartFile file,
		@RequestParam(value = "dateOrder", required = false) String dateOrder
	) {
		return roomAllocationService.preview(scheduleGroupId, file, dateOrder);
	}
}
