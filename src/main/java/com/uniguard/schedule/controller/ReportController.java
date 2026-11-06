package com.uniguard.schedule.controller;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uniguard.schedule.audit.AuditCategory;
import com.uniguard.schedule.audit.AuditEntry;
import com.uniguard.schedule.audit.AuditService;
import com.uniguard.schedule.service.ReportService;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Reports for one exam period (every university role may read them; they show what the schedule already shows). */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

	private final ReportService reportService;
	private final AuditService auditService;

	public ReportController(ReportService reportService, AuditService auditService) {
		this.reportService = reportService;
		this.auditService = auditService;
	}

	@GetMapping("/workload")
	public ReportService.WorkloadReport workload(@RequestParam UUID scheduleGroupId) {
		return reportService.workload(scheduleGroupId);
	}

	/** Sessions and hours per person as CSV (opens in Excel; Arabic names display correctly). */
	@GetMapping("/workload/export")
	public ResponseEntity<byte[]> exportWorkload(@RequestParam UUID scheduleGroupId) {
		String csv = reportService.workloadCsv(scheduleGroupId);
		String period = reportService.scheduleGroupName(scheduleGroupId);
		auditService.record(AuditEntry.success("WORKLOAD_EXPORTED", AuditCategory.SCHEDULES, "SCHEDULE_GROUP", scheduleGroupId,
			"Exported sessions and hours per person for '" + period + "'"));
		String filename = "workload-" + slug(period) + ".csv";
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
			.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
			.body(csv.getBytes(StandardCharsets.UTF_8));
	}

	@GetMapping("/analytics")
	public ReportService.Analytics analytics(@RequestParam UUID scheduleGroupId) {
		return reportService.analytics(scheduleGroupId);
	}

	private static String slug(String value) {
		String slug = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
		return slug.isEmpty() ? "exam-period" : slug;
	}
}
