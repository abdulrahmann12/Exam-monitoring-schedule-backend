package schedule.example.schedule.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import schedule.example.schedule.config.ApplicationDefaults;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.schedulegroup.ScheduleNotifyResponse;
import schedule.example.schedule.entity.InvigilatorAssignment;
import schedule.example.schedule.entity.Person;
import schedule.example.schedule.entity.RoomAssignment;
import schedule.example.schedule.entity.ScheduleGroup;
import schedule.example.schedule.entity.Settings;
import schedule.example.schedule.entity.TimeSlot;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.repository.ScheduleGroupRepository;
import schedule.example.schedule.repository.SettingsRepository;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ScheduleNotificationService {

	private static final Logger log = LoggerFactory.getLogger(ScheduleNotificationService.class);
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

	private final ScheduleGroupRepository scheduleGroupRepository;
	private final RoomAssignmentRepository roomAssignmentRepository;
	private final SettingsRepository settingsRepository;
	private final MailService mailService;
	private final DemoModeService demoModeService;

	public ScheduleNotificationService(
		ScheduleGroupRepository scheduleGroupRepository,
		RoomAssignmentRepository roomAssignmentRepository,
		SettingsRepository settingsRepository,
		MailService mailService,
		DemoModeService demoModeService
	) {
		this.scheduleGroupRepository = scheduleGroupRepository;
		this.roomAssignmentRepository = roomAssignmentRepository;
		this.settingsRepository = settingsRepository;
		this.mailService = mailService;
		this.demoModeService = demoModeService;
	}

	public ScheduleNotifyResponse notifyAssignedStaff(UUID groupId) {
		demoModeService.rejectIfDemoUser("notify-schedule-group");

		ScheduleGroup group = scheduleGroupRepository.findById(groupId)
			.orElseThrow(() -> new NotFoundException(MessageFormat.format(Messages.SCHEDULE_GROUP_NOT_FOUND, groupId)));
		Settings settings = settingsRepository.findById(ApplicationDefaults.DEFAULT_SETTINGS_ID).orElse(null);
		List<RoomAssignment> assignments = roomAssignmentRepository.findAllDetailedByScheduleGroupId(groupId);

		Map<UUID, RecipientSchedule> recipients = new LinkedHashMap<>();
		for (RoomAssignment assignment : assignments) {
			addDuty(recipients, assignment.getChiefInvigilator(), assignment, "Chief Invigilator");
			for (InvigilatorAssignment invigilatorAssignment : assignment.getInvigilatorAssignments()) {
				addDuty(recipients, invigilatorAssignment.getInvigilator(), assignment, "Invigilator");
			}
		}

		int sent = 0;
		int skipped = 0;
		int failed = 0;
		List<String> skippedNames = new ArrayList<>();
		List<String> failedNames = new ArrayList<>();

		for (RecipientSchedule recipient : recipients.values()) {
			recipient.duties.sort(DUTY_ORDER);
			String email = recipient.person.getEmail();
			if (email == null || email.isBlank()) {
				skipped += 1;
				skippedNames.add(recipient.person.getName());
				continue;
			}

			try {
				mailService.sendHtmlWithPdf(
					email,
					buildSubject(settings, group),
					buildHtmlBody(settings, group, recipient),
					buildPdfFilename(group, recipient.person),
					buildSchedulePdf(settings, group, recipient)
				);
				sent += 1;
			} catch (RuntimeException ex) {
				log.warn("Failed to notify '{}' <{}> for group '{}'", recipient.person.getName(), email, group.getName(), ex);
				failed += 1;
				failedNames.add(recipient.person.getName());
			}
		}

		return new ScheduleNotifyResponse(sent, skipped, failed, List.copyOf(skippedNames), List.copyOf(failedNames));
	}

	private static void addDuty(
		Map<UUID, RecipientSchedule> recipients,
		Person person,
		RoomAssignment assignment,
		String role
	) {
		if (person == null) {
			return;
		}

		RecipientSchedule recipient = recipients.computeIfAbsent(person.getId(), id -> new RecipientSchedule(person));
		TimeSlot slot = assignment.getTimeSlot();
		recipient.duties.add(new DutyRow(
			assignment.getExamDate(),
			slot != null && slot.getLabel() != null && !slot.getLabel().isBlank() ? slot.getLabel() : "Time slot",
			slot != null ? slot.getStartTime() : LocalTime.MIDNIGHT,
			slot != null ? slot.getEndTime() : LocalTime.MIDNIGHT,
			assignment.getRoom() != null ? assignment.getRoom().getName() : "Room",
			assignment.getSubjectName(),
			assignment.getSubjectCode(),
			role
		));
	}

	private static String buildSubject(Settings settings, ScheduleGroup group) {
		String systemName = settings != null && settings.getSystemName() != null && !settings.getSystemName().isBlank()
			? settings.getSystemName()
			: "Uni-Guard";
		return systemName + ": your invigilation schedule — " + group.getName();
	}

	private static String buildHtmlBody(Settings settings, ScheduleGroup group, RecipientSchedule recipient) {
		String university = settings != null ? nullToEmpty(settings.getUniversityName()) : "";
		String department = settings != null ? nullToEmpty(settings.getDepartment()) : "";
		String systemName = settings != null ? nullToEmpty(settings.getSystemName()) : "Uni-Guard";

		return """
			<div style="font-family:Segoe UI,Arial,sans-serif;color:#1f2937;line-height:1.5">
			  <p style="margin:0 0 4px;font-size:12px;color:#6b7280">%s%s</p>
			  <h2 style="margin:0 0 16px;font-size:20px">%s</h2>
			  <p>Hello %s,</p>
			  <p>Your invigilation schedule for <strong>%s</strong> is attached as a PDF. It lists only the rooms assigned to you.</p>
			  <p style="margin-top:16px;font-size:12px;color:#6b7280">Sent by %s. Please contact the exam office if anything looks incorrect.</p>
			</div>
			""".formatted(
			escape(university),
			department.isBlank() ? "" : " · " + escape(department),
			escape(group.getName()),
			escape(recipient.person.getName()),
			escape(group.getName()),
			escape(systemName)
		);
	}

	private static byte[] buildSchedulePdf(Settings settings, ScheduleGroup group, RecipientSchedule recipient) {
		String university = settings != null ? nullToEmpty(settings.getUniversityName()) : "";
		String department = settings != null ? nullToEmpty(settings.getDepartment()) : "";
		String systemName = settings != null && settings.getSystemName() != null && !settings.getSystemName().isBlank()
			? settings.getSystemName()
			: "Uni-Guard";

		Font titleFont = resolveFont(16, Font.BOLD, new Color(31, 41, 55));
		Font subtitleFont = resolveFont(10, Font.NORMAL, new Color(107, 114, 128));
		Font bodyFont = resolveFont(11, Font.NORMAL, new Color(31, 41, 55));
		Font headerFont = resolveFont(9, Font.BOLD, new Color(31, 41, 55));
		Font cellFont = resolveFont(9, Font.NORMAL, new Color(31, 41, 55));
		Color headerBg = new Color(243, 244, 246);
		Color border = new Color(229, 231, 235);

		try {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
			PdfWriter.getInstance(document, output);
			document.open();

			if (!university.isBlank() || !department.isBlank()) {
				Paragraph branding = new Paragraph(
					university + (department.isBlank() ? "" : " · " + department),
					subtitleFont
				);
				branding.setSpacingAfter(4);
				document.add(branding);
			}

			Paragraph title = new Paragraph(group.getName(), titleFont);
			title.setSpacingAfter(6);
			document.add(title);

			Paragraph intro = new Paragraph(
				"Invigilation schedule for " + recipient.person.getName() + " (" + recipient.duties.size() + " duty rows).",
				bodyFont
			);
			intro.setSpacingAfter(14);
			document.add(intro);

			PdfPTable table = new PdfPTable(new float[] { 14, 8, 22, 16, 26, 14 });
			table.setWidthPercentage(100);
			addHeaderCell(table, "Date", headerFont, headerBg, border);
			addHeaderCell(table, "Day", headerFont, headerBg, border);
			addHeaderCell(table, "Session", headerFont, headerBg, border);
			addHeaderCell(table, "Room", headerFont, headerBg, border);
			addHeaderCell(table, "Subject", headerFont, headerBg, border);
			addHeaderCell(table, "Role", headerFont, headerBg, border);

			List<DutyRow> orderedDuties = recipient.duties.stream()
				.sorted(DUTY_ORDER)
				.toList();
			for (DutyRow duty : orderedDuties) {
				addBodyCell(table, duty.date.format(DATE_FORMAT), cellFont, border);
				addBodyCell(table, duty.date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), cellFont, border);
				addBodyCell(table, duty.slotLabel + " " + duty.startTime.format(TIME_FORMAT) + "–" + duty.endTime.format(TIME_FORMAT), cellFont, border);
				addBodyCell(table, duty.roomName, cellFont, border);
				addBodyCell(table, formatSubject(duty.subjectName, duty.subjectCode), cellFont, border);
				addBodyCell(table, duty.role, cellFont, border);
			}

			document.add(table);

			Paragraph footer = new Paragraph(
				"Sent by " + systemName + ". Please contact the exam office if anything looks incorrect.",
				subtitleFont
			);
			footer.setSpacingBefore(16);
			document.add(footer);

			document.close();
			return output.toByteArray();
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to generate schedule PDF for " + recipient.person.getName(), ex);
		}
	}

	private static void addHeaderCell(PdfPTable table, String text, Font font, Color background, Color border) {
		PdfPCell cell = new PdfPCell(new Phrase(text, font));
		cell.setBackgroundColor(background);
		cell.setBorderColor(border);
		cell.setPadding(7);
		cell.setHorizontalAlignment(Element.ALIGN_LEFT);
		table.addCell(cell);
	}

	private static void addBodyCell(PdfPTable table, String text, Font font, Color border) {
		PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
		cell.setBorderColor(border);
		cell.setPadding(6);
		table.addCell(cell);
	}

	private static Font resolveFont(float size, int style, Color color) {
		String windir = System.getenv("WINDIR");
		String[] candidates = {
			windir != null ? windir + "/Fonts/arial.ttf" : null,
			"C:/Windows/Fonts/arial.ttf",
			"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
			"/System/Library/Fonts/Supplemental/Arial.ttf"
		};
		for (String path : candidates) {
			if (path == null) {
				continue;
			}
			try {
				BaseFont baseFont = BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
				return new Font(baseFont, size, style, color);
			} catch (Exception ignored) {
				// Try the next installed Unicode font.
			}
		}
		return FontFactory.getFont(FontFactory.HELVETICA, size, style, color);
	}

	private static String buildPdfFilename(ScheduleGroup group, Person person) {
		String groupPart = slug(group.getName());
		String personPart = slug(person.getName());
		return "schedule-" + groupPart + "-" + personPart + ".pdf";
	}

	private static String slug(String value) {
		if (value == null || value.isBlank()) {
			return "schedule";
		}
		String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");
		return slug.isBlank() ? "schedule" : slug;
	}

	private static String formatSubject(String name, String code) {
		if ((name == null || name.isBlank()) && (code == null || code.isBlank())) {
			return "—";
		}
		if (name == null || name.isBlank()) {
			return code;
		}
		if (code == null || code.isBlank()) {
			return name;
		}
		return name + " (" + code + ")";
	}

	private static String escape(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return value
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;");
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static final Comparator<DutyRow> DUTY_ORDER = Comparator
		.comparing(DutyRow::date, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(DutyRow::startTime, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(DutyRow::endTime, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(DutyRow::roomName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

	private static final class RecipientSchedule {
		private final Person person;
		private final List<DutyRow> duties = new ArrayList<>();

		private RecipientSchedule(Person person) {
			this.person = person;
		}
	}

	private record DutyRow(
		LocalDate date,
		String slotLabel,
		LocalTime startTime,
		LocalTime endTime,
		String roomName,
		String subjectName,
		String subjectCode,
		String role
	) {
	}
}
