package com.uniguard.schedule.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.dto.bulk.BulkUploadErrorDTO;
import com.uniguard.schedule.dto.bulk.ScheduleBulkUploadResultDTO;
import com.uniguard.schedule.entity.Room;
import com.uniguard.schedule.entity.RoomAssignment;
import com.uniguard.schedule.entity.ScheduleGroup;
import com.uniguard.schedule.entity.TimeSlot;
import com.uniguard.schedule.entity.enums.AssignmentSource;
import com.uniguard.schedule.exception.BulkRowValidationException;
import com.uniguard.schedule.exception.ValidationException;
import com.uniguard.schedule.repository.RoomAssignmentRepository;
import com.uniguard.schedule.repository.RoomRepository;
import com.uniguard.schedule.repository.TimeSlotRepository;
import com.uniguard.schedule.util.TabularFile;
import com.uniguard.schedule.util.NameNormalizationUtil;

/**
 * Backend implementation of "Bulk Schedule Generation via Excel Upload":
 *
 * <ul>
 *   <li><b>Phase 1</b> — {@link #createBulkTemplate(UUID)} generates a {@code .xlsx} skeleton
 *       (Date, Time Slot, Room, Subject Name, Subject Code) with in-Excel dropdown/date
 *       validation scoped to the schedule group's time slots and the system's active rooms.</li>
 *   <li><b>Phase 2</b> — {@link #uploadBulkSchedule(UUID, MultipartFile)} parses the filled-in
 *       workbook, defensively validates every row against the database, and — only if every row
 *       is valid — creates the {@link RoomAssignment} rows and hands them to
 *       {@link AutoAssignmentService} to staff automatically.</li>
 * </ul>
 */
@Service
@Validated
@RequiredArgsConstructor
public class ScheduleBulkUploadService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleBulkUploadService.class);

	/** Canonical Date column format, both for parsing uploads and rendering the template. */
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
	/** Canonical Time Slot display format used in the dropdown, e.g. "08:00 - 11:00". */
	private static final DateTimeFormatter SLOT_DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
	private static final String SLOT_SEPARATOR = " - ";
	/** Number of data rows (below the header) that receive dropdown/date validation in the template. */
	private static final int TEMPLATE_VALIDATION_ROWS = 5000;
	private static final int SUBJECT_NAME_MAX_LENGTH = 160;
	private static final int SUBJECT_CODE_MAX_LENGTH = 40;

	private final ScheduleGroupSupport scheduleGroupSupport;
	private final TimeSlotRepository timeSlotRepository;
	private final RoomRepository roomRepository;
	private final RoomAssignmentRepository roomAssignmentRepository;
	private final DemoModeService demoModeService;
	private final AutoAssignmentService autoAssignmentService;
	private final ScheduleWriteLock scheduleWriteLock;
	private final SchedulingContextLoader contextLoader;

	// ═══════════════════════════════ Phase 1: Template ═══════════════════════════════

	public byte[] createBulkTemplate(UUID scheduleGroupId) {
		ScheduleGroup group = scheduleGroupSupport.requireGroup(scheduleGroupId);

		List<TimeSlot> slots = timeSlotRepository.findByScheduleGroupIdAndActiveTrueOrderBySortOrderAsc(group.getId());
		if (slots.isEmpty()) {
			throw new ValidationException(Messages.SCHEDULE_BULK_TEMPLATE_NO_SLOTS);
		}

		List<Room> rooms = roomRepository.findAllByActiveTrueOrderByNameAsc();
		if (rooms.isEmpty()) {
			throw new ValidationException(Messages.SCHEDULE_BULK_TEMPLATE_NO_ROOMS);
		}

		List<String> slotLabels = slots.stream().map(ScheduleBulkUploadService::formatSlot).toList();
		List<String> roomNames = rooms.stream().map(Room::getName).toList();

		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			// "Schedule" MUST be sheet index 0 — ExcelParserUtil.parse() always reads sheet 0,
			// and the hidden "Lists" sheet must not shift that index on re-upload.
			Sheet dataSheet = workbook.createSheet("Schedule");
			Sheet listSheet = workbook.createSheet("Lists");

			writeListColumn(listSheet, 0, "Time Slots", slotLabels);
			writeListColumn(listSheet, 1, "Rooms", roomNames);
			workbook.setSheetVisibility(workbook.getSheetIndex(listSheet), SheetVisibility.HIDDEN);

			buildDataSheet(workbook, dataSheet, slotLabels, roomNames);
			workbook.setActiveSheet(workbook.getSheetIndex(dataSheet));

			workbook.write(outputStream);
			return outputStream.toByteArray();
		} catch (IOException exception) {
			throw new ValidationException("The Excel template could not be generated.");
		}
	}

	private void buildDataSheet(XSSFWorkbook workbook, Sheet sheet, List<String> slotLabels, List<String> roomNames) {
		List<String> headers = List.of("Date (YYYY-MM-DD)", "Time Slot", "Room", "Subject Name", "Subject Code", "Students (optional)");
		CellStyle headerStyle = createHeaderStyle(workbook);

		Row headerRow = sheet.createRow(0);
		for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
			Cell cell = headerRow.createCell(columnIndex);
			cell.setCellValue(headers.get(columnIndex));
			cell.setCellStyle(headerStyle);
		}

		// Applied as the column's default style so ANY row the user later fills in (not just
		// pre-created rows) renders its date as yyyy-mm-dd, regardless of Excel locale.
		CellStyle dateStyle = workbook.createCellStyle();
		dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
		sheet.setDefaultColumnStyle(0, dateStyle);

		Row exampleRow = sheet.createRow(1);
		Cell exampleDateCell = exampleRow.createCell(0);
		exampleDateCell.setCellValue(LocalDate.now().plusDays(7));
		exampleDateCell.setCellStyle(dateStyle);
		exampleRow.createCell(1).setCellValue(slotLabels.get(0));
		exampleRow.createCell(2).setCellValue(roomNames.get(0));
		exampleRow.createCell(3).setCellValue("Example Subject");
		exampleRow.createCell(4).setCellValue("EX101");
		exampleRow.createCell(5).setCellValue(25);

		int firstDataRow = 1;
		int lastDataRow = TEMPLATE_VALIDATION_ROWS;

		addDateValidation(sheet, firstDataRow, lastDataRow, 0);
		addListValidation(sheet, firstDataRow, lastDataRow, 1, "'Lists'!$A$2:$A$" + (slotLabels.size() + 1));
		addListValidation(sheet, firstDataRow, lastDataRow, 2, "'Lists'!$B$2:$B$" + (roomNames.size() + 1));

		sheet.createFreezePane(0, 1);
		for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
			sheet.autoSizeColumn(columnIndex);
		}
	}

	private void writeListColumn(Sheet sheet, int column, String header, List<String> values) {
		Row headerRow = sheet.getRow(0);
		if (headerRow == null) {
			headerRow = sheet.createRow(0);
		}
		headerRow.createCell(column).setCellValue(header);

		for (int index = 0; index < values.size(); index++) {
			Row row = sheet.getRow(index + 1);
			if (row == null) {
				row = sheet.createRow(index + 1);
			}
			row.createCell(column).setCellValue(values.get(index));
		}
	}

	private void addDateValidation(Sheet sheet, int firstRow, int lastRow, int column) {
		DataValidationHelper helper = sheet.getDataValidationHelper();
		DataValidationConstraint constraint = helper.createDateConstraint(
				DataValidationConstraint.OperatorType.BETWEEN,
				"DATE(2020,1,1)",
				"DATE(2035,12,31)",
				"yyyy-mm-dd"
		);
		CellRangeAddressList addressList = new CellRangeAddressList(firstRow, lastRow, column, column);
		DataValidation validation = helper.createValidation(constraint, addressList);
		validation.setSuppressDropDownArrow(false);
		validation.setShowErrorBox(true);
		validation.createErrorBox("Invalid date", "Enter a valid date between 2020-01-01 and 2035-12-31, formatted as YYYY-MM-DD.");
		validation.createPromptBox("Date", "Format: YYYY-MM-DD (e.g. " + LocalDate.now().plusDays(7).format(DATE_FORMAT) + ").");
		validation.setShowPromptBox(true);
		sheet.addValidationData(validation);
	}

	private void addListValidation(Sheet sheet, int firstRow, int lastRow, int column, String listFormula) {
		DataValidationHelper helper = sheet.getDataValidationHelper();
		DataValidationConstraint constraint = helper.createFormulaListConstraint(listFormula);
		CellRangeAddressList addressList = new CellRangeAddressList(firstRow, lastRow, column, column);
		DataValidation validation = helper.createValidation(constraint, addressList);
		// XSSF has inverted semantics: true = SHOW the dropdown arrow
		validation.setSuppressDropDownArrow(true);
		validation.setShowErrorBox(true);
		validation.createErrorBox("Invalid value", "Please select a value from the dropdown list.");
		validation.setShowPromptBox(true);
		validation.createPromptBox("Selection", "Pick a value from the dropdown list.");
		sheet.addValidationData(validation);
	}

	private CellStyle createHeaderStyle(XSSFWorkbook workbook) {
		CellStyle style = workbook.createCellStyle();
		Font font = workbook.createFont();
		font.setBold(true);
		style.setFont(font);
		style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
		style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		return style;
	}

	private static String formatSlot(TimeSlot slot) {
		return slot.getStartTime().format(SLOT_DISPLAY_TIME_FORMAT) + SLOT_SEPARATOR + slot.getEndTime().format(SLOT_DISPLAY_TIME_FORMAT);
	}

	// ═══════════════════════════════ Phase 2: Upload ═══════════════════════════════

	/** Upper limit of data rows per file (keeps one upload inside one reasonable transaction). */
	private static final int MAX_IMPORT_ROWS = 5000;

	/**
	 * Imports an exam timetable from Excel or CSV: our template, or an export from a
	 * student-information system (Banner, PeopleSoft, Odoo) — columns are found by their header names.
	 * Missing time slots are created; rooms must already exist (their capacity is unknown).
	 * All or nothing: if any row is wrong, nothing is saved and every problem is listed.
	 *
	 * @param dateOrder "DAY_FIRST" / "MONTH_FIRST" for dates like 03/04/2027; empty = decide from the file
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ScheduleBulkUploadResultDTO uploadBulkSchedule(UUID scheduleGroupId, MultipartFile file, String dateOrder) {
		demoModeService.rejectIfDemoUser("bulk-upload-schedule");
		ScheduleGroup group = scheduleGroupSupport.requireGroup(scheduleGroupId);
		scheduleWriteLock.acquire();

		TabularFile.Table table = TabularFile.read(file, MAX_IMPORT_ROWS);
		LOGGER.info("Schedule bulk upload started: groupId={}, fileSizeBytes={}, rows={}", group.getId(), file.getSize(), table.rows().size());
		if (table.rows().isEmpty()) {
			throw new ValidationException(Messages.SCHEDULE_BULK_UPLOAD_EMPTY);
		}
		TimetableColumns columns = TimetableColumns.of(table.headers());
		TimetableColumns.DateOrder order = parseDateOrder(dateOrder);
		if (order == null) {
			order = TimetableColumns.detectOrder(table.rows().stream()
				.map(row -> columns.cell(row, TimetableColumns.Field.DATE))
				.filter(cell -> cell.date() == null)
				.map(TabularFile.CellValue::text)
				.toList());
		}

		List<TimeSlot> groupSlots = timeSlotRepository.findByScheduleGroupIdAndActiveTrueOrderBySortOrderAsc(group.getId());
		Map<String, TimeSlot> slotsByTimes = new LinkedHashMap<>();
		for (TimeSlot slot : groupSlots) {
			slotsByTimes.putIfAbsent(timesKey(slot.getStartTime(), slot.getEndTime()), slot);
		}
		int nextSortOrder = groupSlots.stream().mapToInt(TimeSlot::getSortOrder).max().orElse(0) + 1;
		List<String> slotsCreated = new ArrayList<>();

		Map<String, Room> roomsByNormalizedName = roomRepository.findAllByActiveTrueOrderByNameAsc().stream()
				.collect(Collectors.toMap(
						room -> NameNormalizationUtil.normalizeForComparison(room.getName()),
						Function.identity(),
						(a, b) -> a,
						LinkedHashMap::new
				));

		List<BulkUploadErrorDTO> errors = new ArrayList<>();
		List<ScheduleRowCommand> validCommands = new ArrayList<>();
		Map<ScheduleRowKey, Integer> seenInFile = new LinkedHashMap<>();

		for (TabularFile.DataRow row : table.rows()) {
			int rowNumber = row.rowNumber();
			List<String> rowErrors = new ArrayList<>();
			LocalDate examDate = TimetableColumns.parseDate(columns.cell(row, TimetableColumns.Field.DATE), order, rowErrors);
			LocalTime[] times = resolveTimes(row, columns, rowErrors);
			Room room = resolveRoom(row, columns, roomsByNormalizedName, rowErrors);
			String subjectName = validateOptionalField(columns.subjectName(row), SUBJECT_NAME_MAX_LENGTH, Messages.SCHEDULE_BULK_ROW_SUBJECT_NAME_TOO_LONG, rowErrors);
			String subjectCode = validateOptionalField(columns.subjectCode(row), SUBJECT_CODE_MAX_LENGTH, Messages.SCHEDULE_BULK_ROW_SUBJECT_CODE_TOO_LONG, rowErrors);
			if (!rowErrors.isEmpty()) {
				rowErrors.forEach(message -> errors.add(new BulkUploadErrorDTO(rowNumber, message)));
				continue;
			}

			String key = timesKey(times[0], times[1]);
			TimeSlot timeSlot = slotsByTimes.get(key);
			if (timeSlot == null) {
				// Student-information systems do not know our slot names: create the slot (undone if the upload fails).
				timeSlot = new TimeSlot();
				timeSlot.setScheduleGroup(group);
				timeSlot.setLabel("Exam " + times[0].format(SLOT_DISPLAY_TIME_FORMAT) + "–" + times[1].format(SLOT_DISPLAY_TIME_FORMAT));
				timeSlot.setStartTime(times[0]);
				timeSlot.setEndTime(times[1]);
				timeSlot.setSortOrder(nextSortOrder++);
				timeSlot.setActive(true);
				timeSlot = timeSlotRepository.save(timeSlot);
				slotsByTimes.put(key, timeSlot);
				slotsCreated.add(timeSlot.getLabel());
			}

			ScheduleRowKey rowKey = new ScheduleRowKey(examDate, timeSlot.getId(), room.getId());
			Integer firstRowNumber = seenInFile.putIfAbsent(rowKey, rowNumber);
			if (firstRowNumber != null) {
				errors.add(new BulkUploadErrorDTO(rowNumber, MessageFormat.format(Messages.SCHEDULE_BULK_ROW_DUPLICATE_IN_FILE, firstRowNumber)));
				continue;
			}
			if (roomAssignmentRepository.existsByScheduleGroupIdAndRoomIdAndTimeSlotIdAndExamDate(group.getId(), room.getId(), timeSlot.getId(), examDate)) {
				errors.add(new BulkUploadErrorDTO(rowNumber, Messages.SCHEDULE_BULK_ROW_ALREADY_EXISTS));
				continue;
			}
			Integer studentCount = parseStudentCount(columns.text(row, TimetableColumns.Field.STUDENTS), room, rowErrors);
			if (!rowErrors.isEmpty()) {
				rowErrors.forEach(message -> errors.add(new BulkUploadErrorDTO(rowNumber, message)));
				continue;
			}
			validCommands.add(new ScheduleRowCommand(rowNumber, examDate, timeSlot, room, subjectName, subjectCode, studentCount));
		}

		List<RoomAssignment> toCreate = new ArrayList<>(validCommands.size());
		for (ScheduleRowCommand command : validCommands) {
			RoomAssignment assignment = new RoomAssignment();
			assignment.setScheduleGroup(group);
			assignment.setExamDate(command.examDate());
			assignment.setRoom(command.room());
			assignment.setTimeSlot(command.timeSlot());
			assignment.setSubjectName(command.subjectName());
			assignment.setSubjectCode(command.subjectCode());
			assignment.setStudentCount(command.studentCount());
			assignment.setLocked(false);
			assignment.setSource(AssignmentSource.GENERATED);
			toCreate.add(assignment);
		}
		detectRoomOverlaps(validCommands, toCreate, errors);

		if (!errors.isEmpty()) {
			errors.sort(java.util.Comparator.comparingInt(BulkUploadErrorDTO::row));
			throw new BulkRowValidationException(
					MessageFormat.format(Messages.SCHEDULE_BULK_UPLOAD_ROW_ERRORS, errors.size()),
					errors
			);
		}

		List<RoomAssignment> saved = roomAssignmentRepository.saveAll(toCreate);
		LOGGER.info("Schedule bulk upload created {} room assignment(s) and {} time slot(s) for group {}.", saved.size(), slotsCreated.size(), group.getId());

		AutoAssignmentService.StaffingResult staffing = autoAssignmentService.assignStaff(group.getId(), saved);

		return new ScheduleBulkUploadResultDTO(
				saved.size(),
				staffing.chiefSlotsTotal(),
				staffing.chiefSlotsFilled(),
				staffing.invigilatorSlotsTotal(),
				staffing.invigilatorSlotsFilled(),
				staffing.warnings(),
				List.copyOf(slotsCreated),
				columns.recognised()
		);
	}

	private static TimetableColumns.DateOrder parseDateOrder(String value) {
		if (value == null || value.isBlank() || "AUTO".equalsIgnoreCase(value)) {
			return null;
		}
		try {
			return TimetableColumns.DateOrder.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			throw new ValidationException("dateOrder must be DAY_FIRST, MONTH_FIRST or empty.");
		}
	}

	private static String timesKey(LocalTime start, LocalTime end) {
		return start.format(SLOT_DISPLAY_TIME_FORMAT) + "-" + end.format(SLOT_DISPLAY_TIME_FORMAT);
	}

	/** Separate start/end columns, or one "09:00 - 12:00" column. */
	private static LocalTime[] resolveTimes(TabularFile.DataRow row, TimetableColumns columns, List<String> errors) {
		LocalTime start = null;
		LocalTime end = null;
		if (columns.has(TimetableColumns.Field.START) && columns.has(TimetableColumns.Field.END)) {
			TabularFile.CellValue startCell = columns.cell(row, TimetableColumns.Field.START);
			TabularFile.CellValue endCell = columns.cell(row, TimetableColumns.Field.END);
			start = TimetableColumns.parseTime(startCell);
			end = TimetableColumns.parseTime(endCell);
			if (start == null) {
				errors.add(startCell.text().isBlank() ? "Start time is required." : "Start time \"" + startCell.text() + "\" is not a time (use e.g. 09:00).");
			}
			if (end == null) {
				errors.add(endCell.text().isBlank() ? "End time is required." : "End time \"" + endCell.text() + "\" is not a time (use e.g. 12:00).");
			}
		} else {
			String text = NameNormalizationUtil.normalizeWhitespace(columns.text(row, TimetableColumns.Field.SLOT));
			if (text == null || text.isBlank()) {
				errors.add(Messages.SCHEDULE_BULK_ROW_SLOT_REQUIRED);
				return null;
			}
			LocalTime[] range = TimetableColumns.parseRange(text);
			if (range == null) {
				errors.add(MessageFormat.format(Messages.SCHEDULE_BULK_ROW_SLOT_INVALID_FORMAT, text));
				return null;
			}
			start = range[0];
			end = range[1];
		}
		if (start == null || end == null) {
			return null;
		}
		if (!end.isAfter(start)) {
			errors.add("The exam must end after it starts (" + start.format(SLOT_DISPLAY_TIME_FORMAT) + " – " + end.format(SLOT_DISPLAY_TIME_FORMAT) + ").");
			return null;
		}
		return new LocalTime[] {start, end};
	}

	/** The room by name; with a building column also "Building Room" (Banner keeps them apart). */
	private Room resolveRoom(TabularFile.DataRow row, TimetableColumns columns, Map<String, Room> roomsByNormalizedName, List<String> errors) {
		String roomText = columns.text(row, TimetableColumns.Field.ROOM);
		if (columns.has(TimetableColumns.Field.BUILDING)) {
			String building = NameNormalizationUtil.normalizeWhitespace(columns.text(row, TimetableColumns.Field.BUILDING));
			String roomOnly = NameNormalizationUtil.normalizeWhitespace(roomText);
			if (building != null && !building.isBlank() && roomOnly != null && !roomOnly.isBlank()) {
				for (String candidate : List.of(building + " " + roomOnly, building + "-" + roomOnly, building + roomOnly)) {
					Room match = roomsByNormalizedName.get(NameNormalizationUtil.normalizeForComparison(candidate));
					if (match != null) {
						return match;
					}
				}
			}
		}
		return parseRoom(roomText, roomsByNormalizedName, errors);
	}

	/**
	 * Rejects rows whose room is already booked at an overlapping time on the same date — in any
	 * schedule group, or by an earlier row of the same file (time slots of different lengths can
	 * overlap even when they are different slots).
	 */
	private void detectRoomOverlaps(List<ScheduleRowCommand> commands, List<RoomAssignment> candidates, List<BulkUploadErrorDTO> errors) {
		if (candidates.isEmpty()) {
			return;
		}
		Set<LocalDate> dates = candidates.stream().map(RoomAssignment::getExamDate).collect(Collectors.toSet());
		SchedulingContext context = contextLoader.load(dates);
		for (int i = 0; i < candidates.size(); i++) {
			RoomAssignment candidate = candidates.get(i);
			var overlap = StaffConcurrencyRules.findRoomOverlap(context.universe(), candidate);
			if (overlap.isPresent()) {
				errors.add(new BulkUploadErrorDTO(commands.get(i).rowNumber(), MessageFormat.format(
						Messages.SCHEDULE_BULK_ROW_ROOM_OVERLAP, StaffConcurrencyRules.describeSlot(overlap.get().getTimeSlot()))));
			} else {
				context.add(candidate);
			}
		}
	}

	private Room parseRoom(String text, Map<String, Room> roomsByNormalizedName, List<String> errors) {
		String normalized = NameNormalizationUtil.normalizeWhitespace(text);
		if (normalized == null || normalized.isBlank()) {
			errors.add(Messages.SCHEDULE_BULK_ROW_ROOM_REQUIRED);
			return null;
		}

		Room match = roomsByNormalizedName.get(NameNormalizationUtil.normalizeForComparison(normalized));
		if (match == null) {
			errors.add(MessageFormat.format(Messages.SCHEDULE_BULK_ROW_ROOM_NOT_FOUND, normalized));
			return null;
		}
		return match;
	}

	private String validateOptionalField(String text, int maxLength, String tooLongMessage, List<String> errors) {
		String normalized = NameNormalizationUtil.normalizeWhitespace(text);
		if (normalized == null || normalized.isBlank()) {
			return null;
		}
		if (normalized.length() > maxLength) {
			errors.add(tooLongMessage);
			return null;
		}
		return normalized;
	}

	/** Optional whole number between 0 and the room's capacity. */
	private static Integer parseStudentCount(String text, Room room, List<String> errors) {
		String normalized = NameNormalizationUtil.normalizeWhitespace(text);
		if (normalized == null || normalized.isBlank()) {
			return null;
		}
		try {
			int value = (int) Double.parseDouble(normalized);
			if (value < 0) {
				errors.add("Students cannot be negative.");
				return null;
			}
			if (value > room.getCapacity()) {
				errors.add(MessageFormat.format(Messages.ASSIGNMENT_STUDENTS_EXCEED_CAPACITY, room.getName(), room.getCapacity(), value));
				return null;
			}
			return value;
		} catch (NumberFormatException exception) {
			errors.add("Students must be a whole number.");
			return null;
		}
	}

	private record ScheduleRowCommand(int rowNumber, LocalDate examDate, TimeSlot timeSlot, Room room, String subjectName, String subjectCode, Integer studentCount) {
	}

	private record ScheduleRowKey(LocalDate examDate, UUID timeSlotId, UUID roomId) {
	}
}
