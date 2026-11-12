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
	private final com.uniguard.schedule.service.RoomAllocationExportService exportService;

	public RoomAllocationController(RoomAllocationService roomAllocationService,
	                                com.uniguard.schedule.service.RoomAllocationExportService exportService) {
		this.roomAllocationService = roomAllocationService;
		this.exportService = exportService;
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
		@RequestParam(value = "dateOrder", required = false) String dateOrder,
		@RequestParam(value = "adjustments", required = false) String adjustments
	) {
		return roomAllocationService.save(scheduleGroupId, file, dateOrder, adjustments);
	}

	/** Saved allocations of an exam period, newest first. */
	@GetMapping("/batches")
	public java.util.List<RoomAllocationResults.Batch> batches(@RequestParam("scheduleGroupId") UUID scheduleGroupId) {
		return roomAllocationService.batches(scheduleGroupId);
	}

	/** Undo one saved allocation: deletes its exam rooms and their staff. */
	@PostMapping("/batches/{id}/undo")
	public RoomAllocationResults.Undone undo(@org.springframework.web.bind.annotation.PathVariable UUID id) {
		return roomAllocationService.undo(id);
	}

	/** Door sheets (PDF): one page per room and time of a saved allocation. */
	@GetMapping("/batches/{id}/door-sheets")
	public ResponseEntity<ByteArrayResource> doorSheets(@org.springframework.web.bind.annotation.PathVariable UUID id) {
		return download(exportService.doorSheets(id), MediaType.APPLICATION_PDF);
	}

	/** Excel file of a saved allocation (rooms, subjects, students, staff). */
	@GetMapping("/batches/{id}/export")
	public ResponseEntity<ByteArrayResource> export(@org.springframework.web.bind.annotation.PathVariable UUID id) {
		return download(exportService.excel(id), MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
	}

	private static ResponseEntity<ByteArrayResource> download(com.uniguard.schedule.service.RoomAllocationExportService.Download file, MediaType type) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(type);
		headers.setContentLength(file.bytes().length);
		headers.setContentDisposition(ContentDisposition.attachment().filename(file.fileName()).build());
		return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(file.bytes()));
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
		@RequestParam(value = "dateOrder", required = false) String dateOrder,
		@RequestParam(value = "adjustments", required = false) String adjustments
	) {
		return roomAllocationService.preview(scheduleGroupId, file, dateOrder, adjustments);
	}
}
