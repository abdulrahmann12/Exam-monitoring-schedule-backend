package schedule.example.schedule.service;

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
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.bulk.BulkUploadErrorDTO;
import schedule.example.schedule.dto.bulk.ScheduleBulkUploadResultDTO;
import schedule.example.schedule.entity.Room;
import schedule.example.schedule.entity.RoomAssignment;
import schedule.example.schedule.entity.ScheduleGroup;
import schedule.example.schedule.entity.TimeSlot;
import schedule.example.schedule.entity.enums.AssignmentSource;
import schedule.example.schedule.exception.BulkRowValidationException;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.repository.RoomRepository;
import schedule.example.schedule.repository.TimeSlotRepository;
import schedule.example.schedule.util.ExcelParserUtil;
import schedule.example.schedule.util.NameNormalizationUtil;

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
	/** Lenient parser that accepts both "8:00" and "08:00" when reading an uploaded cell back. */
	private static final DateTimeFormatter SLOT_PARSE_TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");
	private static final String SLOT_SEPARATOR = " - ";
	/** Number of data rows (below the header) that receive dropdown/date validation in the template. */
	private static final int TEMPLATE_VALIDATION_ROWS = 5000;
	private static final int SUBJECT_NAME_MAX_LENGTH = 160;
	private static final int SUBJECT_CODE_MAX_LENGTH = 40;

	private final ScheduleGroupSupport scheduleGroupSupport;
	private final TimeSlotRepository timeSlotRepository;
	private final RoomRepository roomRepository;
	private final RoomAssignmentRepository roomAssignmentRepository;
	private final ExcelParserUtil excelParserUtil;
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
		List<String> headers = List.of("Date (YYYY-MM-DD)", "Time Slot", "Room", "Subject Name", "Subject Code");
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

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ScheduleBulkUploadResultDTO uploadBulkSchedule(UUID scheduleGroupId, MultipartFile file) {
		demoModeService.rejectIfDemoUser("bulk-upload-schedule");
		ScheduleGroup group = scheduleGroupSupport.requireGroup(scheduleGroupId);
		scheduleWriteLock.acquire();

		ExcelParserUtil.ParseResult<ScheduleRowSource> parseResult = excelParserUtil.parse(
				file,
				values -> new ScheduleRowSource(
						values.getString(0),
						values.getString(1),
						values.getString(2),
						values.getString(3),
						values.getString(4)
				)
		);

		LOGGER.info("Schedule bulk upload started: groupId={}, fileSizeBytes={}, rows={}",
				group.getId(), file.getSize(), parseResult.totalRows());

		if (parseResult.rows().isEmpty()) {
			throw new ValidationException(Messages.SCHEDULE_BULK_UPLOAD_EMPTY);
		}

		Map<String, TimeSlot> slotsByFormattedLabel = timeSlotRepository
				.findByScheduleGroupIdAndActiveTrueOrderBySortOrderAsc(group.getId()).stream()
				.collect(Collectors.toMap(ScheduleBulkUploadService::formatSlot, Function.identity(), (a, b) -> a, LinkedHashMap::new));

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

		for (ExcelParserUtil.ParsedRow<ScheduleRowSource> parsedRow : parseResult.rows()) {
			validateRow(parsedRow, group.getId(), slotsByFormattedLabel, roomsByNormalizedName, seenInFile, errors, validCommands);
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
		LOGGER.info("Schedule bulk upload created {} room assignment(s) for group {}.", saved.size(), group.getId());

		AutoAssignmentService.StaffingResult staffing = autoAssignmentService.assignStaff(group.getId(), saved);

		return new ScheduleBulkUploadResultDTO(
				saved.size(),
				staffing.chiefSlotsTotal(),
				staffing.chiefSlotsFilled(),
				staffing.invigilatorSlotsTotal(),
				staffing.invigilatorSlotsFilled(),
				staffing.warnings()
		);
	}

	/** Validates one parsed row, appending to either {@code errors} or {@code validCommands}. */
	private void validateRow(
			ExcelParserUtil.ParsedRow<ScheduleRowSource> parsedRow,
			UUID groupId,
			Map<String, TimeSlot> slotsByFormattedLabel,
			Map<String, Room> roomsByNormalizedName,
			Map<ScheduleRowKey, Integer> seenInFile,
			List<BulkUploadErrorDTO> errors,
			List<ScheduleRowCommand> validCommands
	) {
		int rowNumber = parsedRow.rowNumber();
		ScheduleRowSource source = parsedRow.value();
		List<String> rowErrors = new ArrayList<>();

		LocalDate examDate = parseDate(source.dateText(), rowErrors);
		TimeSlot timeSlot = parseTimeSlot(source.timeSlotText(), slotsByFormattedLabel, rowErrors);
		Room room = parseRoom(source.roomText(), roomsByNormalizedName, rowErrors);
		String subjectName = validateOptionalField(source.subjectName(), SUBJECT_NAME_MAX_LENGTH, Messages.SCHEDULE_BULK_ROW_SUBJECT_NAME_TOO_LONG, rowErrors);
		String subjectCode = validateOptionalField(source.subjectCode(), SUBJECT_CODE_MAX_LENGTH, Messages.SCHEDULE_BULK_ROW_SUBJECT_CODE_TOO_LONG, rowErrors);

		if (!rowErrors.isEmpty()) {
			rowErrors.forEach(message -> errors.add(new BulkUploadErrorDTO(rowNumber, message)));
			return;
		}

		ScheduleRowKey key = new ScheduleRowKey(examDate, timeSlot.getId(), room.getId());
		Integer firstRowNumber = seenInFile.putIfAbsent(key, rowNumber);
		if (firstRowNumber != null) {
			errors.add(new BulkUploadErrorDTO(rowNumber, MessageFormat.format(Messages.SCHEDULE_BULK_ROW_DUPLICATE_IN_FILE, firstRowNumber)));
			return;
		}

		if (roomAssignmentRepository.existsByScheduleGroupIdAndRoomIdAndTimeSlotIdAndExamDate(groupId, room.getId(), timeSlot.getId(), examDate)) {
			errors.add(new BulkUploadErrorDTO(rowNumber, Messages.SCHEDULE_BULK_ROW_ALREADY_EXISTS));
			return;
		}

		validCommands.add(new ScheduleRowCommand(rowNumber, examDate, timeSlot, room, subjectName, subjectCode));
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

	private LocalDate parseDate(String text, List<String> errors) {
		String normalized = NameNormalizationUtil.normalizeWhitespace(text);
		if (normalized == null || normalized.isBlank()) {
			errors.add(Messages.SCHEDULE_BULK_ROW_DATE_REQUIRED);
			return null;
		}
		try {
			return LocalDate.parse(normalized, DATE_FORMAT);
		} catch (DateTimeParseException exception) {
			errors.add(MessageFormat.format(Messages.SCHEDULE_BULK_ROW_DATE_INVALID, normalized));
			return null;
		}
	}

	private TimeSlot parseTimeSlot(String text, Map<String, TimeSlot> slotsByFormattedLabel, List<String> errors) {
		String normalized = NameNormalizationUtil.normalizeWhitespace(text);
		if (normalized == null || normalized.isBlank()) {
			errors.add(Messages.SCHEDULE_BULK_ROW_SLOT_REQUIRED);
			return null;
		}

		String[] parts = normalized.split("\\s*-\\s*", 2);
		if (parts.length != 2) {
			errors.add(MessageFormat.format(Messages.SCHEDULE_BULK_ROW_SLOT_INVALID_FORMAT, normalized));
			return null;
		}

		LocalTime start;
		LocalTime end;
		try {
			start = LocalTime.parse(parts[0].trim(), SLOT_PARSE_TIME_FORMAT);
			end = LocalTime.parse(parts[1].trim(), SLOT_PARSE_TIME_FORMAT);
		} catch (DateTimeParseException exception) {
			errors.add(MessageFormat.format(Messages.SCHEDULE_BULK_ROW_SLOT_INVALID_FORMAT, normalized));
			return null;
		}

		String key = start.format(SLOT_DISPLAY_TIME_FORMAT) + SLOT_SEPARATOR + end.format(SLOT_DISPLAY_TIME_FORMAT);
		TimeSlot match = slotsByFormattedLabel.get(key);
		if (match == null) {
			errors.add(MessageFormat.format(Messages.SCHEDULE_BULK_ROW_SLOT_NOT_FOUND, normalized));
			return null;
		}
		return match;
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

	private record ScheduleRowSource(String dateText, String timeSlotText, String roomText, String subjectName, String subjectCode) {
	}

	private record ScheduleRowCommand(int rowNumber, LocalDate examDate, TimeSlot timeSlot, Room room, String subjectName, String subjectCode) {
	}

	private record ScheduleRowKey(LocalDate examDate, UUID timeSlotId, UUID roomId) {
	}
}
