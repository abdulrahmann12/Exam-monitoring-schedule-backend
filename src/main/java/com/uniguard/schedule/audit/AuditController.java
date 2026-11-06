package com.uniguard.schedule.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uniguard.schedule.config.PageRequestFactory;
import com.uniguard.schedule.dto.common.PageResponse;
import com.uniguard.schedule.entity.AuditEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The activity log. Administrators only (enforced in {@code SecurityConfig}); read-only by design. */
@RestController
@RequestMapping("/api/audit-events")
public class AuditController {

	private final AuditQueryService queryService;
	private final AuditService auditService;
	private final PageRequestFactory pageRequestFactory;

	public AuditController(AuditQueryService queryService, AuditService auditService, PageRequestFactory pageRequestFactory) {
		this.queryService = queryService;
		this.auditService = auditService;
		this.pageRequestFactory = pageRequestFactory;
	}

	public record AuditEventResponse(
		UUID id,
		Instant occurredAt,
		String actorEmail,
		String action,
		String category,
		String targetType,
		String targetId,
		String summary,
		String outcome,
		String ipAddress
	) {
		public static AuditEventResponse of(AuditEvent e) {
			return new AuditEventResponse(e.getId(), e.getOccurredAt(), e.getActorEmail(), e.getAction(), e.getCategory(),
				e.getTargetType(), e.getTargetId(), e.getSummary(), e.getOutcome(), e.getIpAddress());
		}
	}

	@GetMapping
	public PageResponse<AuditEventResponse> search(
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "50") int size,
		@RequestParam(required = false) String category,
		@RequestParam(required = false) String actor,
		@RequestParam(required = false) String action,
		@RequestParam(required = false) String outcome,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
		@RequestParam(required = false) String q
	) {
		Pageable pageable = pageRequestFactory.create(page, size, "occurredAt", Sort.Direction.DESC, Set.of("occurredAt"));
		var filter = new AuditQueryService.Filter(category, actor, action, outcome, from, to, q);
		return PageResponse.from(queryService.search(filter, pageable).map(AuditEventResponse::of));
	}

	/** CSV for Excel (UTF-8 with BOM so Arabic names display correctly), newest first, at most 10,000 rows. */
	@GetMapping("/export")
	public ResponseEntity<byte[]> export(
		@RequestParam(required = false) String category,
		@RequestParam(required = false) String actor,
		@RequestParam(required = false) String action,
		@RequestParam(required = false) String outcome,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
		@RequestParam(required = false) String q
	) {
		var filter = new AuditQueryService.Filter(category, actor, action, outcome, from, to, q);
		List<AuditEvent> events = queryService.export(filter);
		StringBuilder csv = new StringBuilder("﻿");
		csv.append(row("Time (UTC)", "User", "Category", "Action", "Summary", "Outcome", "IP address", "Target type", "Target id"));
		for (AuditEvent e : events) {
			csv.append(row(e.getOccurredAt().toString(), e.getActorEmail(), e.getCategory(), e.getAction(), e.getSummary(),
				e.getOutcome(), e.getIpAddress(), e.getTargetType(), e.getTargetId()));
		}
		auditService.record(AuditEntry.success("ACTIVITY_LOG_EXPORTED", AuditCategory.SECURITY, null, null,
			"Exported the activity log (" + events.size() + " entries)"));

		String filename = "activity-log-" + LocalDate.now(ZoneOffset.UTC) + ".csv";
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
			.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
			.body(csv.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static String row(String... cells) {
		StringBuilder line = new StringBuilder();
		for (int i = 0; i < cells.length; i++) {
			if (i > 0) {
				line.append(',');
			}
			line.append(cell(cells[i]));
		}
		return line.append("\r\n").toString();
	}

	/**
	 * Quotes every cell, and neutralises values that a spreadsheet would run as a formula
	 * (=, +, -, @, tab, CR at the start) — a person's name like "=HYPERLINK(...)" stays plain text.
	 */
	static String cell(String value) {
		if (value == null) {
			return "\"\"";
		}
		String text = value;
		if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
			text = "'" + text;
		}
		return "\"" + text.replace("\"", "\"\"") + "\"";
	}
}
