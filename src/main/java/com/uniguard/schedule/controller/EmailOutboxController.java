package com.uniguard.schedule.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.uniguard.schedule.config.PageRequestFactory;
import com.uniguard.schedule.dto.common.PageResponse;
import com.uniguard.schedule.repository.EmailMessageRepository;
import com.uniguard.schedule.service.DemoModeService;
import com.uniguard.schedule.service.EmailOutboxService;
import com.uniguard.schedule.tenant.TenantContext;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Email delivery log of the signed-in user's university (administrators and schedulers). */
@RestController
@RequestMapping("/api/email-outbox")
public class EmailOutboxController {

	private final EmailOutboxService outbox;
	private final PageRequestFactory pageRequestFactory;
	private final DemoModeService demoModeService;

	public EmailOutboxController(EmailOutboxService outbox, PageRequestFactory pageRequestFactory, DemoModeService demoModeService) {
		this.outbox = outbox;
		this.pageRequestFactory = pageRequestFactory;
		this.demoModeService = demoModeService;
	}

	public record Counts(Map<String, Long> counts) {
	}

	@GetMapping
	public PageResponse<EmailMessageRepository.Summary> list(
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "50") int size,
		@RequestParam(required = false) String status
	) {
		Pageable pageable = pageRequestFactory.create(page, size, "createdAt", Sort.Direction.DESC, Set.of("createdAt"));
		// Sorting is fixed in the query; only page and size are taken from the request.
		return PageResponse.from(outbox.page(TenantContext.require(), status, org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())));
	}

	@GetMapping("/counts")
	public Counts counts() {
		return new Counts(outbox.counts(TenantContext.require()));
	}

	@PostMapping("/{id}/retry")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void retry(@PathVariable UUID id) {
		demoModeService.rejectIfDemoUser("retry-email");
		outbox.retry(TenantContext.require(), id);
	}

	@PostMapping("/retry-failed")
	public Map<String, Integer> retryFailed() {
		demoModeService.rejectIfDemoUser("retry-emails");
		return Map.of("queued", outbox.retryAllFailed(TenantContext.require()));
	}
}
