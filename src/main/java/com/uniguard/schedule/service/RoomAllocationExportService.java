package com.uniguard.schedule.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;

import com.uniguard.schedule.entity.InvigilatorAssignment;
import com.uniguard.schedule.entity.RoomAllocationBatch;
import com.uniguard.schedule.entity.RoomAssignment;
import com.uniguard.schedule.entity.Settings;
import com.uniguard.schedule.exception.ConflictException;
import com.uniguard.schedule.exception.NotFoundException;
import com.uniguard.schedule.exception.ValidationException;
import com.uniguard.schedule.repository.RoomAllocationBatchRepository;
import com.uniguard.schedule.repository.RoomAssignmentRepository;
import com.uniguard.schedule.repository.SettingsRepository;
import com.uniguard.schedule.tenant.TenantContext;
import com.uniguard.schedule.util.PdfFonts;

/**
 * Printouts of a saved room allocation: door sheets (one page per room and time, to put on the door) and an Excel
 * file of the whole plan. Both show the exam rooms of that save that still exist, with their staff.
 */
@Service
@RequiredArgsConstructor
public class RoomAllocationExportService {

	private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
	private static final Color INK = new Color(17, 24, 39);
	private static final Color MUTED = new Color(100, 116, 139);
	private static final Color LINE = new Color(203, 213, 225);
	private static final Color BRAND = new Color(30, 58, 138);

	private final RoomAllocationBatchRepository batchRepository;
	private final RoomAssignmentRepository roomAssignmentRepository;
	private final SettingsRepository settingsRepository;

	/** A file to download. */
	public record Download(String fileName, byte[] bytes) {
	}

	@Transactional(readOnly = true)
	public Download doorSheets(UUID batchId) {
		RoomAllocationBatch batch = requireBatch(batchId);
		List<RoomAssignment> rooms = rooms(batch);
		String university = settingsRepository.findById(TenantContext.require()).map(Settings::getUniversityName).orElse(null);
		String period = batch.getScheduleGroup().getName();

		Font small = PdfFonts.resolve(11, Font.NORMAL, MUTED);
		Font roomFont = PdfFonts.resolve(72, Font.BOLD, BRAND);
		Font sub = PdfFonts.resolve(16, Font.NORMAL, MUTED);
		Font when = PdfFonts.resolve(26, Font.BOLD, INK);
		Font label = PdfFonts.resolve(12, Font.BOLD, MUTED);
		Font value = PdfFonts.resolve(20, Font.NORMAL, INK);
		Font subject = PdfFonts.resolve(30, Font.BOLD, INK);

		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Document document = new Document(PageSize.A4, 48, 48, 48, 48);
			PdfWriter.getInstance(document, out);
			document.addTitle("Door sheets – " + period);
			document.open();
			boolean first = true;
			for (RoomAssignment room : rooms) {
				if (!first) {
					document.newPage();
				}
				first = false;
				Paragraph top = new Paragraph((university == null || university.isBlank() ? "" : university + " · ") + period, small);
				top.setSpacingAfter(36);
				document.add(top);

				Paragraph name = new Paragraph(room.getRoom().getName(), roomFont);
				name.setAlignment(Element.ALIGN_CENTER);
				document.add(name);
				String building = room.getRoom().buildingKey().isEmpty() ? "" : "Building " + room.getRoom().getBuilding().trim() + " · ";
				Paragraph seats = new Paragraph(building + room.getRoom().getCapacity() + " seats", sub);
				seats.setAlignment(Element.ALIGN_CENTER);
				seats.setSpacingAfter(36);
				document.add(seats);

				Paragraph date = new Paragraph(room.getExamDate().format(LONG_DATE), when);
				date.setAlignment(Element.ALIGN_CENTER);
				document.add(date);
				Paragraph time = new Paragraph(room.getTimeSlot().getStartTime().format(HH_MM) + " – " + room.getTimeSlot().getEndTime().format(HH_MM), when);
				time.setAlignment(Element.ALIGN_CENTER);
				time.setSpacingAfter(40);
				document.add(time);

				Paragraph exam = new Paragraph(subjectText(room), subject);
				exam.setAlignment(Element.ALIGN_CENTER);
				exam.setSpacingAfter(30);
				document.add(exam);

				PdfPTable table = new PdfPTable(new float[] {1.2f, 3f});
				table.setWidthPercentage(100);
				row(table, "Students", (room.getStudentCount() == null ? "–" : room.getStudentCount().toString()) + " of " + room.getRoom().getCapacity() + " seats", label, value);
				row(table, "Chief", room.getChiefInvigilator() == null ? "–" : room.getChiefInvigilator().getName(), label, value);
				row(table, "Invigilators", invigilators(room, "\n"), label, value);
				document.add(table);
			}
			document.close();
			return new Download("door-sheets-" + slug(period) + ".pdf", out.toByteArray());
		} catch (IOException | com.lowagie.text.DocumentException exception) {
			throw new ValidationException("The door sheets could not be made.");
		}
	}

	@Transactional(readOnly = true)
	public Download excel(UUID batchId) {
		RoomAllocationBatch batch = requireBatch(batchId);
		List<RoomAssignment> rooms = rooms(batch);
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet("Room allocation");
			CellStyle header = workbook.createCellStyle();
			org.apache.poi.ss.usermodel.Font bold = workbook.createFont();
			bold.setBold(true);
			header.setFont(bold);
			header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
			header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
			CellStyle dateStyle = workbook.createCellStyle();
			dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));

			List<String> headers = List.of("Date", "Day", "Time", "Room", "Building", "Room Type", "Subject Name", "Subject Code",
				"Students", "Seats", "Chief", "Invigilators");
			Row headerRow = sheet.createRow(0);
			for (int c = 0; c < headers.size(); c++) {
				headerRow.createCell(c).setCellValue(headers.get(c));
				headerRow.getCell(c).setCellStyle(header);
			}
			int r = 1;
			for (RoomAssignment room : rooms) {
				Row row = sheet.createRow(r++);
				row.createCell(0).setCellValue(room.getExamDate());
				row.getCell(0).setCellStyle(dateStyle);
				row.createCell(1).setCellValue(room.getExamDate().getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH));
				row.createCell(2).setCellValue(room.getTimeSlot().getStartTime().format(HH_MM) + " - " + room.getTimeSlot().getEndTime().format(HH_MM));
				row.createCell(3).setCellValue(room.getRoom().getName());
				row.createCell(4).setCellValue(room.getRoom().buildingKey().isEmpty() ? "" : room.getRoom().getBuilding().trim());
				row.createCell(5).setCellValue(room.getRoom().getCategory() == null ? "" : room.getRoom().getCategory().getName());
				row.createCell(6).setCellValue(safe(room.getSubjectName()));
				row.createCell(7).setCellValue(safe(room.getSubjectCode()));
				if (room.getStudentCount() != null) {
					row.createCell(8).setCellValue(room.getStudentCount());
				}
				row.createCell(9).setCellValue(room.getRoom().getCapacity());
				row.createCell(10).setCellValue(room.getChiefInvigilator() == null ? "" : safe(room.getChiefInvigilator().getName()));
				row.createCell(11).setCellValue(safe(invigilators(room, ", ")));
			}
			int[] widths = {12, 11, 14, 16, 10, 16, 34, 14, 10, 8, 26, 48};
			for (int c = 0; c < widths.length; c++) {
				sheet.setColumnWidth(c, widths[c] * 256);
			}
			sheet.createFreezePane(0, 1);
			sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(r - 1, 0), 0, headers.size() - 1));
			workbook.write(out);
			return new Download("room-allocation-" + slug(batch.getScheduleGroup().getName()) + ".xlsx", out.toByteArray());
		} catch (IOException exception) {
			throw new ValidationException("The Excel file could not be made.");
		}
	}

	private RoomAllocationBatch requireBatch(UUID batchId) {
		RoomAllocationBatch batch = batchRepository.findById(batchId)
			.orElseThrow(() -> new NotFoundException("This saved allocation was not found."));
		if (batch.getUndoneAt() != null) {
			throw new ConflictException("This allocation was undone: there is nothing to print.");
		}
		return batch;
	}

	/** The save's exam rooms that still exist, by date, time, building and room. */
	private List<RoomAssignment> rooms(RoomAllocationBatch batch) {
		List<RoomAssignment> rooms = roomAssignmentRepository.findAllDetailedByAllocationBatchId(batch.getId()).stream()
			.sorted(Comparator.comparing(RoomAssignment::getExamDate)
				.thenComparing(room -> room.getTimeSlot().getStartTime())
				.thenComparing(room -> room.getRoom().buildingKey())
				.thenComparing(room -> room.getRoom().getName()))
			.toList();
		if (rooms.isEmpty()) {
			throw new ConflictException("The exam rooms of this allocation no longer exist: there is nothing to print.");
		}
		return rooms;
	}

	private static String subjectText(RoomAssignment room) {
		String name = room.getSubjectName();
		String code = room.getSubjectCode();
		if (name != null && code != null) {
			return name + " (" + code + ")";
		}
		return Objects.requireNonNullElse(name != null ? name : code, "Exam");
	}

	private static String invigilators(RoomAssignment room, String separator) {
		String names = room.getInvigilatorAssignments().stream()
			.map(InvigilatorAssignment::getInvigilator)
			.filter(Objects::nonNull)
			.map(person -> person.getName())
			.collect(Collectors.joining(separator));
		return names.isEmpty() ? "–" : names;
	}

	private static void row(PdfPTable table, String name, String text, Font label, Font value) {
		PdfPCell left = new PdfPCell(new Phrase(name, label));
		left.setBorderColor(LINE);
		left.setPadding(10);
		left.setVerticalAlignment(Element.ALIGN_MIDDLE);
		table.addCell(left);
		PdfPCell right = new PdfPCell(new Phrase(text, value));
		right.setBorderColor(LINE);
		right.setPadding(10);
		table.addCell(right);
	}

	/** Text cells only: a name starting with = + - @ must not become an Excel formula. */
	private static String safe(String text) {
		if (text == null) {
			return "";
		}
		return !text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
	}

	private static String slug(String text) {
		String slug = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
		return slug.isEmpty() ? "exam-period" : slug;
	}
}
