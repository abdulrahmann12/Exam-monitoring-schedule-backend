package schedule.example.schedule.controller;

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
import schedule.example.schedule.dto.bulk.BulkDuplicateStrategy;
import schedule.example.schedule.dto.bulk.BulkUploadResultDTO;
import schedule.example.schedule.service.BulkUploadService;

@RestController
@RequestMapping("/api/bulk")
public class BulkUploadController {

	private static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	private final BulkUploadService bulkUploadService;

	public BulkUploadController(BulkUploadService bulkUploadService) {
		this.bulkUploadService = bulkUploadService;
	}

	@PostMapping(value = "/persons/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public BulkUploadResultDTO uploadPersons(
		@RequestParam("file") MultipartFile file,
		@RequestParam(defaultValue = "AUTO_RENAME") BulkDuplicateStrategy duplicateStrategy
	) {
		return bulkUploadService.uploadPersons(file, duplicateStrategy);
	}

	@PostMapping(value = "/rooms/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public BulkUploadResultDTO uploadRooms(
		@RequestParam("file") MultipartFile file,
		@RequestParam(defaultValue = "AUTO_RENAME") BulkDuplicateStrategy duplicateStrategy
	) {
		return bulkUploadService.uploadRooms(file, duplicateStrategy);
	}

	@GetMapping("/persons/template")
	public ResponseEntity<ByteArrayResource> downloadPersonsTemplate() {
		return createTemplateResponse(bulkUploadService.createPersonsTemplate(), "persons-bulk-upload-template.xlsx");
	}

	@GetMapping("/rooms/template")
	public ResponseEntity<ByteArrayResource> downloadRoomsTemplate() {
		return createTemplateResponse(bulkUploadService.createRoomsTemplate(), "rooms-bulk-upload-template.xlsx");
	}

	private ResponseEntity<ByteArrayResource> createTemplateResponse(byte[] templateBytes, String fileName) {
		ByteArrayResource resource = new ByteArrayResource(templateBytes);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE));
		headers.setContentLength(templateBytes.length);
		headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());

		return ResponseEntity.ok()
			.headers(headers)
			.body(resource);
	}
}