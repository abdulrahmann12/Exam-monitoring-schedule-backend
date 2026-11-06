package schedule.example.schedule.owner;

import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import schedule.example.schedule.audit.AuditController.AuditEventResponse;
import schedule.example.schedule.config.PageRequestFactory;
import schedule.example.schedule.dto.common.PageResponse;
import schedule.example.schedule.owner.OwnerDtos.AddAdminRequest;
import schedule.example.schedule.owner.OwnerDtos.AdminInvitation;
import schedule.example.schedule.owner.OwnerDtos.CreateUniversityRequest;
import schedule.example.schedule.owner.OwnerDtos.Overview;
import schedule.example.schedule.owner.OwnerDtos.UniversitySummary;
import schedule.example.schedule.owner.OwnerDtos.UpdateUniversityRequest;

import java.util.Set;
import java.util.UUID;

/** Owner panel. Only the platform owner (SUPER_ADMIN) reaches it — see SecurityConfig. */
@RestController
@RequestMapping("/api/owner")
public class OwnerController {

	private final PlatformOwnerService ownerService;
	private final PageRequestFactory pageRequestFactory;

	public OwnerController(PlatformOwnerService ownerService, PageRequestFactory pageRequestFactory) {
		this.ownerService = ownerService;
		this.pageRequestFactory = pageRequestFactory;
	}

	@GetMapping("/universities")
	public Overview overview() {
		return ownerService.overview();
	}

	@PostMapping("/universities")
	@ResponseStatus(HttpStatus.CREATED)
	public AdminInvitation create(@Valid @RequestBody CreateUniversityRequest request) {
		return ownerService.createUniversity(request);
	}

	@PutMapping("/universities/{id}")
	public UniversitySummary update(@PathVariable UUID id, @Valid @RequestBody UpdateUniversityRequest request) {
		return ownerService.updateUniversity(id, request);
	}

	@PostMapping("/universities/{id}/admins")
	@ResponseStatus(HttpStatus.CREATED)
	public AdminInvitation addAdmin(@PathVariable UUID id, @Valid @RequestBody AddAdminRequest request) {
		return ownerService.addAdministrator(id, request);
	}

	@GetMapping("/activity")
	public PageResponse<AuditEventResponse> activity(
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "50") int size
	) {
		Pageable pageable = pageRequestFactory.create(page, size, "occurredAt", Sort.Direction.DESC, Set.of("occurredAt"));
		return PageResponse.from(ownerService.activity(pageable).map(AuditEventResponse::of));
	}
}
