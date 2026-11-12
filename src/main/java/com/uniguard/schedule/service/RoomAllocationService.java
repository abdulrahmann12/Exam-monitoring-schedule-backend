package com.uniguard.schedule.service;

import java.math.BigDecimal;
import java.text.MessageFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.dto.allocation.RoomAllocationPreviewDTO;
import com.uniguard.schedule.dto.allocation.RoomAllocationResults;
import com.uniguard.schedule.entity.enums.AssignmentSource;
import com.uniguard.schedule.entity.enums.HallGender;
import com.uniguard.schedule.exception.NotFoundException;
import com.uniguard.schedule.dto.bulk.BulkUploadErrorDTO;
import com.uniguard.schedule.entity.Person;
import com.uniguard.schedule.entity.Room;
import com.uniguard.schedule.entity.RoomAllocationBatch;
import com.uniguard.schedule.entity.enums.PersonRole;
import com.uniguard.schedule.entity.RoomCategory;
import com.uniguard.schedule.entity.RoomAssignment;
import com.uniguard.schedule.entity.ScheduleGroup;
import com.uniguard.schedule.entity.TimeSlot;
import com.uniguard.schedule.exception.BulkRowValidationException;
import com.uniguard.schedule.exception.ValidationException;
import com.uniguard.schedule.repository.RoomRepository;
import com.uniguard.schedule.repository.TimeSlotRepository;
import com.uniguard.schedule.util.NameNormalizationUtil;
import com.uniguard.schedule.util.RoomNames;
import com.uniguard.schedule.util.TabularFile;

/**
 * Room allocation: the admin uploads an exam list (date, time, subject, code, students — no rooms)
 * and the system proposes rooms with {@link RoomAllocator}.
 *
 * <p>Free rooms for a sitting are the active rooms not already booked at an overlapping time on that
 * date, in any exam period. Sittings of the same file that overlap (09:00–12:00 and 11:00–13:00)
 * share the rooms correctly, because each allocated sitting is added to the booked rooms before the
 * next one is planned.
 *
 * <p>{@link #preview} writes nothing. Row problems fail the whole file with every problem listed,
 * like the timetable import.
 */
@Service
@RequiredArgsConstructor
public class RoomAllocationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(RoomAllocationService.class);

	private static final int MAX_ROWS = 5000;
	private static final int SUBJECT_NAME_MAX_LENGTH = 160;
	private static final int SUBJECT_CODE_MAX_LENGTH = 40;
	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	private final ScheduleGroupSupport scheduleGroupSupport;
	private final TimeSlotRepository timeSlotRepository;
	private final RoomRepository roomRepository;
	private final SchedulingContextLoader contextLoader;
	private final RoomCategoryService roomCategoryService;
	private final com.uniguard.schedule.repository.RoomAssignmentRepository roomAssignmentRepository;
	private final ScheduleWriteLock scheduleWriteLock;
	private final DemoModeService demoModeService;
	private final AutoAssignmentService autoAssignmentService;
	private final com.uniguard.schedule.repository.RoomAllocationBatchRepository batchRepository;
	private final StaffConcurrencyRules staffConcurrencyRules;
	private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
	private final com.uniguard.schedule.repository.PersonRepository personRepository;

	/**
	 * @param dateOrder "DAY_FIRST" / "MONTH_FIRST" for dates like 03/04/2027; empty = decide from the file
	 */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public RoomAllocationPreviewDTO preview(UUID scheduleGroupId, MultipartFile file, String dateOrder, String adjustments) {
		ScheduleGroup group = scheduleGroupSupport.requireGroup(scheduleGroupId);
		Plan plan = plan(file, dateOrder, adjustments);
		LOGGER.info("Room allocation preview: groupId={}, exams={}, sittings={}", group.getId(), plan.exams, plan.sittings.size());
		return plan.toPreview();
	}

	// ═══════════════════════════════ Save rooms ═══════════════════════════════

	/**
	 * Saves the proposed rooms: plans again from the same file inside the schedule lock (so nothing booked
	 * since the preview is double-booked), then creates the missing time slots and one exam room per room
	 * used, without staff. All or nothing. Exams without enough rooms keep their seated part and are reported.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public RoomAllocationResults.Saved save(UUID scheduleGroupId, MultipartFile file, String dateOrder, String adjustments) {
		demoModeService.rejectIfDemoUser("room-allocation-save");
		ScheduleGroup group = scheduleGroupSupport.requireGroup(scheduleGroupId);
		scheduleWriteLock.acquire();
		Plan plan = plan(file, dateOrder, adjustments);

		int nextSortOrder = timeSlotRepository.findAll().stream().mapToInt(TimeSlot::getSortOrder).max().orElse(0) + 1;
		List<String> slotsCreated = new ArrayList<>();
		List<RoomAssignment> toCreate = new ArrayList<>();
		int examsSaved = 0;
		int examsIncomplete = 0;
		int unplaced = 0;
		LocalDate firstDate = null;
		for (PlannedSitting sitting : plan.sittings) {
			TimeSlot slot = sitting.slot();
			if (slot.getId() == null) { // the same new slot object is shared by every sitting with these times
				slot.setSortOrder(nextSortOrder++);
				timeSlotRepository.save(slot);
				slotsCreated.add(slot.getLabel());
			}
			for (RoomAllocator.Placement<Room> placement : sitting.placements()) {
				ExamRow row = sitting.rows().get(Integer.parseInt(placement.exam().key()));
				examsSaved += placement.rooms().isEmpty() ? 0 : 1;
				examsIncomplete += placement.unplaced() > 0 ? 1 : 0;
				unplaced += placement.unplaced();
				for (RoomAllocator.Seating<Room> seating : placement.rooms()) {
					RoomAssignment assignment = new RoomAssignment();
					assignment.setScheduleGroup(group);
					assignment.setExamDate(sitting.key().date());
					assignment.setTimeSlot(slot);
					assignment.setRoom(seating.room().id());
					assignment.setSubjectName(row.subjectName());
					assignment.setSubjectCode(row.subjectCode());
					assignment.setStudentCount(seating.students());
					assignment.setLocked(false);
					assignment.setSource(AssignmentSource.GENERATED);
					toCreate.add(assignment);
				}
			}
			if (firstDate == null || sitting.key().date().isBefore(firstDate)) {
				firstDate = sitting.key().date();
			}
		}
		RoomAllocationBatch batch = new RoomAllocationBatch();
		batch.setScheduleGroup(group);
		batch.setFileName(fileName(file));
		batch.setCreatedBy(currentUserEmail());
		batch.setExams(plan.exams);
		batch.setExamRooms(toCreate.size());
		batch.setStudentsUnplaced(unplaced);
		batch = batchRepository.save(batch);
		for (RoomAssignment assignment : toCreate) {
			assignment.setAllocationBatchId(batch.getId());
		}
		List<RoomAssignment> saved = roomAssignmentRepository.saveAll(toCreate);
		LOGGER.info("Room allocation saved: groupId={}, examRooms={}, slotsCreated={}, unplacedStudents={}",
			group.getId(), saved.size(), slotsCreated.size(), unplaced);
		return new RoomAllocationResults.Saved(batch.getId(), saved.size(), saved.stream().map(RoomAssignment::getId).toList(),
			examsSaved, examsIncomplete, unplaced, List.copyOf(slotsCreated), List.copyOf(plan.warnings), firstDate, plan.toPreview());
	}

	// ═══════════════════════════════ Saved allocations and Undo ═══════════════════════════════

	@Transactional(readOnly = true)
	public List<RoomAllocationResults.Batch> batches(UUID scheduleGroupId) {
		ScheduleGroup group = scheduleGroupSupport.requireGroup(scheduleGroupId);
		return batchRepository.findAllByScheduleGroupIdOrderByCreatedAtDesc(group.getId()).stream()
			.map(batch -> new RoomAllocationResults.Batch(batch.getId(), batch.getCreatedAt(), batch.getCreatedBy(), batch.getFileName(),
				batch.getExams(), batch.getExamRooms(), batch.getStudentsUnplaced(),
				batch.getUndoneAt() == null ? roomAssignmentRepository.countByAllocationBatchId(batch.getId()) : 0, batch.getUndoneAt()))
			.toList();
	}

	/**
	 * Deletes every exam room (and its staff) that one "Save rooms" created and still exists, also if it was
	 * changed in the Scheduler since. People's duty counters are corrected. New time slots stay (they may be in
	 * use elsewhere). All or nothing.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public RoomAllocationResults.Undone undo(UUID batchId) {
		demoModeService.rejectIfDemoUser("room-allocation-undo");
		scheduleWriteLock.acquire();
		RoomAllocationBatch batch = batchRepository.findById(batchId)
			.orElseThrow(() -> new NotFoundException("This saved allocation was not found."));
		if (batch.getUndoneAt() != null) {
			throw new com.uniguard.schedule.exception.ConflictException("This allocation was already undone.");
		}
		List<RoomAssignment> rooms = roomAssignmentRepository.findAllDetailedByAllocationBatchId(batch.getId());
		Map<UUID, Integer> previous = StaffConcurrencyRules.countAssignmentOccurrences(rooms);
		roomAssignmentRepository.deleteAll(rooms);
		staffConcurrencyRules.applyWorkloadDelta(previous, Map.of());
		batch.setUndoneAt(java.time.Instant.now());
		batchRepository.save(batch);
		LOGGER.info("Room allocation undone: batch={}, examRoomsDeleted={}", batch.getId(), rooms.size());
		return new RoomAllocationResults.Undone(batch.getId(), batch.getFileName(), rooms.size());
	}

	private static String fileName(MultipartFile file) {
		String name = file.getOriginalFilename();
		if (name == null || name.isBlank()) {
			return null;
		}
		name = name.replace('\\', '/');
		name = name.substring(name.lastIndexOf('/') + 1).trim();
		return name.length() > 255 ? name.substring(0, 255) : name;
	}

	private static String currentUserEmail() {
		var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
		return auth == null ? null : auth.getName();
	}

	// ═══════════════════════════════ Create schedule ═══════════════════════════════

	/**
	 * "Create schedule": assigns chiefs and invigilators to the saved exam rooms with the normal scheduling
	 * engine (same rules as everywhere: no double booking, leave, daily limits, fair hours). Exam rooms that
	 * already have someone assigned are skipped, so pressing the button twice never staffs a room twice.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public RoomAllocationResults.Staffed staff(RoomAllocationResults.StaffRequest request) {
		demoModeService.rejectIfDemoUser("room-allocation-staff");
		ScheduleGroup group = scheduleGroupSupport.requireGroup(request.scheduleGroupId());
		Set<UUID> ids = new java.util.LinkedHashSet<>(request.assignmentIds());
		List<RoomAssignment> found = roomAssignmentRepository.findAllDetailedByIdIn(ids);
		if (found.size() != ids.size()) {
			throw new NotFoundException("Some of these exam rooms no longer exist. Choose the rooms again.");
		}
		for (RoomAssignment assignment : found) {
			if (assignment.getScheduleGroup() == null || !group.getId().equals(assignment.getScheduleGroup().getId())) {
				throw new ValidationException("These exam rooms belong to another exam period.");
			}
		}
		List<RoomAssignment> empty = found.stream()
			.filter(assignment -> assignment.getChiefInvigilator() == null
				&& assignment.getInvigilatorAssignments().stream().allMatch(seat -> seat.getInvigilator() == null))
			.toList();
		AutoAssignmentService.StaffingResult result = autoAssignmentService.assignStaff(group.getId(), new ArrayList<>(empty));
		LOGGER.info("Room allocation staffed: groupId={}, examRooms={}, skipped={}", group.getId(), empty.size(), found.size() - empty.size());
		return new RoomAllocationResults.Staffed(empty.size(), found.size() - empty.size(),
			result.chiefSlotsTotal(), result.chiefSlotsFilled(), result.invigilatorSlotsTotal(), result.invigilatorSlotsFilled(), result.warnings(),
			staffHints(empty));
	}

	// ═══════════════════════════════ Template ═══════════════════════════════

	private static final int TEMPLATE_ROWS = 2000;

	/**
	 * The exam-list template: Date (date check), Day (filled from the date), Time Slot (dropdown of this
	 * university's active time slots), Subject Name, Subject Code, Number of Students (whole number check).
	 */
	@Transactional(readOnly = true)
	public byte[] createTemplate() {
		List<TimeSlot> slots = timeSlotRepository.findByActiveTrueOrderBySortOrderAsc().stream()
			.sorted(Comparator.comparing(TimeSlot::getStartTime).thenComparing(TimeSlot::getEndTime))
			.toList();
		if (slots.isEmpty()) {
			throw new ValidationException(Messages.SCHEDULE_BULK_TEMPLATE_NO_SLOTS);
		}
		List<String> slotLabels = slots.stream()
			.map(slot -> slot.getStartTime().format(HH_MM) + " - " + slot.getEndTime().format(HH_MM))
			.distinct()
			.toList();

		try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
		     java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
			// The data sheet must stay first: the upload reads sheet 0.
			org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Exams");
			org.apache.poi.ss.usermodel.Sheet lists = workbook.createSheet("Lists");
			lists.createRow(0).createCell(0).setCellValue("Time Slots");
			List<String> roomTypes = roomCategoryService.names();
			lists.getRow(0).createCell(1).setCellValue("Room Types");
			for (int i = 0; i < Math.max(slotLabels.size(), roomTypes.size()); i++) {
				org.apache.poi.ss.usermodel.Row listRow = lists.getRow(i + 1) != null ? lists.getRow(i + 1) : lists.createRow(i + 1);
				if (i < slotLabels.size()) {
					listRow.createCell(0).setCellValue(slotLabels.get(i));
				}
				if (i < roomTypes.size()) {
					listRow.createCell(1).setCellValue(roomTypes.get(i));
				}
			}
			workbook.setSheetVisibility(workbook.getSheetIndex(lists), org.apache.poi.ss.usermodel.SheetVisibility.HIDDEN);

			org.apache.poi.ss.usermodel.CellStyle header = workbook.createCellStyle();
			org.apache.poi.ss.usermodel.Font bold = workbook.createFont();
			bold.setBold(true);
			header.setFont(bold);
			header.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
			header.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
			org.apache.poi.ss.usermodel.CellStyle dateStyle = workbook.createCellStyle();
			dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));

			List<String> headers = List.of("Date", "Day", "Time Slot", "Subject Name", "Subject Code", "Number of Students", "Room Type", "Gender");
			org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
			for (int c = 0; c < headers.size(); c++) {
				headerRow.createCell(c).setCellValue(headers.get(c));
				headerRow.getCell(c).setCellStyle(header);
			}
			sheet.setDefaultColumnStyle(0, dateStyle);

			// Day follows the date by itself (in the language of the user's Excel; English and Arabic are checked).
			for (int r = 1; r <= TEMPLATE_ROWS; r++) {
				org.apache.poi.ss.usermodel.Row row = sheet.createRow(r);
				row.createCell(1).setCellFormula("IF(A" + (r + 1) + "=\"\",\"\",TEXT(A" + (r + 1) + ",\"dddd\"))");
			}
			org.apache.poi.ss.usermodel.Row example = sheet.getRow(1);
			org.apache.poi.ss.usermodel.Cell exampleDate = example.createCell(0);
			exampleDate.setCellValue(LocalDate.now().plusDays(14));
			exampleDate.setCellStyle(dateStyle);
			example.createCell(2).setCellValue(slotLabels.get(0));
			example.createCell(3).setCellValue("Example Subject");
			example.createCell(4).setCellValue("EX101");
			example.createCell(5).setCellValue(120);


			org.apache.poi.ss.usermodel.DataValidationHelper helper = sheet.getDataValidationHelper();
			var dates = helper.createValidation(
				helper.createDateConstraint(org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType.BETWEEN, "DATE(2020,1,1)", "DATE(2035,12,31)", "yyyy-mm-dd"),
				new org.apache.poi.ss.util.CellRangeAddressList(1, TEMPLATE_ROWS, 0, 0));
			dates.setShowErrorBox(true);
			dates.createErrorBox("Invalid date", "Enter a date, e.g. 2027-01-10.");
			sheet.addValidationData(dates);

			var slotList = helper.createValidation(
				helper.createFormulaListConstraint("'Lists'!$A$2:$A$" + (slotLabels.size() + 1)),
				new org.apache.poi.ss.util.CellRangeAddressList(1, TEMPLATE_ROWS, 2, 2));
			slotList.setSuppressDropDownArrow(true); // XSSF: true = show the arrow
			slotList.setShowErrorBox(true);
			slotList.createErrorBox("Unknown time slot", "Pick a time slot from the list (Time Slots page in Uni-Guard), or leave it empty.");
			slotList.setShowPromptBox(true);
			slotList.createPromptBox("Time slot", "Pick a time, or leave it empty: Uni-Guard then chooses the best time on that date.");
			sheet.addValidationData(slotList);

			var students = helper.createValidation(
				helper.createIntegerConstraint(org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType.BETWEEN, "1", Integer.toString(RoomAllocator.MAX_STUDENTS_PER_EXAM)),
				new org.apache.poi.ss.util.CellRangeAddressList(1, TEMPLATE_ROWS, 5, 5));
			students.setShowErrorBox(true);
			students.createErrorBox("Number of students", "Enter a whole number of students (at least 1).");
			sheet.addValidationData(students);

			// Empty = a normal room. A type (e.g. "Computer Lab") = only rooms of exactly that type.
			if (!roomTypes.isEmpty()) {
				var typeChoice = helper.createValidation(
					helper.createFormulaListConstraint("'Lists'!$B$2:$B$" + (roomTypes.size() + 1)),
					new org.apache.poi.ss.util.CellRangeAddressList(1, TEMPLATE_ROWS, 6, 6));
				typeChoice.setSuppressDropDownArrow(true);
				typeChoice.setShowErrorBox(true);
				typeChoice.createErrorBox("Room type", "Pick a room type from the list, or leave it empty for a normal room.");
				typeChoice.setShowPromptBox(true);
				typeChoice.createPromptBox("Room type", "Empty = a normal room. Choose a type (e.g. Computer Lab) when the exam needs exactly that kind of room.");
				sheet.addValidationData(typeChoice);
			}

			sheet.createFreezePane(0, 1);
			// Gender: Male / Female halls first for that gender, then Mixed halls; empty = any hall.
			var genderChoice = helper.createValidation(
				helper.createExplicitListConstraint(new String[] {"Male", "Female"}),
				new org.apache.poi.ss.util.CellRangeAddressList(1, TEMPLATE_ROWS, 7, 7));
			genderChoice.setSuppressDropDownArrow(true);
			genderChoice.setShowErrorBox(true);
			genderChoice.createErrorBox("Gender", "Choose Male or Female, or leave it empty for a mixed exam.");
			genderChoice.setShowPromptBox(true);
			genderChoice.createPromptBox("Gender", "Male / Female: halls of that gender first, then Mixed halls. Empty = any hall. Men and women of one subject: two rows.");
			sheet.addValidationData(genderChoice);

			int[] widths = {14, 14, 18, 36, 16, 20, 20, 12};
			for (int c = 0; c < widths.length; c++) {
				sheet.setColumnWidth(c, widths[c] * 256);
			}
			workbook.setActiveSheet(0);
			workbook.write(out);
			return out.toByteArray();
		} catch (java.io.IOException exception) {
			throw new ValidationException("The Excel template could not be created.");
		}
	}

	// ═══════════════════════════════ Reading the file ═══════════════════════════════

	/**
	 * @param roomType  the room type asked for; null = a normal room (special types are never given to it)
	 * @param autoTime  true = no time in the file: the system chooses a time slot on that date (start/end are null)
	 * @param gender    MALE/FEMALE: halls of that gender first, then Mixed halls; null = any hall (Mixed first)
	 */
	private record ExamRow(int row, LocalDate date, LocalTime start, LocalTime end, String subjectName, String subjectCode, int students,
		RoomCategory roomType, boolean autoTime, HallGender gender, List<UUID> pinnedRooms, boolean edited, boolean fillMore) {
	}

	/**
	 * Changes the user made on the page before saving: another time for a row ("HH:mm-HH:mm") and/or the exact rooms
	 * for a row. They are applied on top of the file every time the plan is made (preview and save).
	 */
	record Adjustments(Map<Integer, String> times, Map<Integer, List<UUID>> rooms, Map<Integer, String> dates, Map<Integer, UUID> anchors) {
		static final Adjustments NONE = new Adjustments(Map.of(), Map.of(), Map.of(), Map.of());

		static Adjustments parse(String json, com.fasterxml.jackson.databind.ObjectMapper mapper) {
			if (json == null || json.isBlank()) {
				return NONE;
			}
			try {
				Adjustments parsed = mapper.readValue(json, Adjustments.class);
				Map<Integer, String> times = parsed.times() == null ? Map.of() : parsed.times();
				Map<Integer, List<UUID>> rooms = parsed.rooms() == null ? Map.of() : parsed.rooms();
				Map<Integer, String> dates = parsed.dates() == null ? Map.of() : parsed.dates();
				Map<Integer, UUID> anchors = parsed.anchors() == null ? Map.of() : parsed.anchors();
				if (times.size() > MAX_ROWS || rooms.size() > MAX_ROWS || dates.size() > MAX_ROWS || anchors.size() > MAX_ROWS
					|| rooms.values().stream().anyMatch(list -> list == null || list.size() > 200)) {
					throw new ValidationException("Too many changes.");
				}
				return new Adjustments(times, rooms, dates, anchors);
			} catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
				throw new ValidationException("Your changes could not be read. Undo them and try again.");
			}
		}
	}

	private List<ExamRow> readRows(TabularFile.Table table, TimetableColumns columns, TimetableColumns.DateOrder order, List<String> warnings,
	                               Adjustments adjustments) {
		List<BulkUploadErrorDTO> errors = new ArrayList<>();
		List<ExamRow> rows = new ArrayList<>();
		Map<String, Integer> seen = new HashMap<>();
		Map<String, RoomCategory> roomTypes = roomCategoryService.byNormalizedName();
		for (TabularFile.DataRow row : table.rows()) {
			int rowNumber = row.rowNumber();
			List<String> rowErrors = new ArrayList<>();
			LocalDate date = TimetableColumns.parseDate(columns.cell(row, TimetableColumns.Field.DATE), order, rowErrors);
			String movedToDay = adjustments.dates().get(rowNumber);
			if (movedToDay != null) { // the user moved this exam to another day on the page
				try {
					date = LocalDate.parse(movedToDay);
				} catch (java.time.format.DateTimeParseException exception) {
					rowErrors.add("The date you chose (" + movedToDay + ") is not a date.");
				}
			}
			boolean autoTime = columns.timeBlank(row);
			LocalTime[] times;
			String movedTo = adjustments.times().get(rowNumber);
			if (movedTo != null) { // the user moved this exam on the page
				times = TimetableColumns.parseRange(movedTo);
				autoTime = false;
				if (times == null || !times[1].isAfter(times[0])) {
					rowErrors.add("The time you chose (" + movedTo + ") is not a time slot.");
				}
			} else {
				times = autoTime ? null : columns.times(row, rowErrors);
			}
			List<UUID> pinned = adjustments.rooms().get(rowNumber);
			UUID anchor = adjustments.anchors().get(rowNumber); // dropped on one room: start there, add rooms when needed
			if (pinned == null && anchor != null) {
				pinned = List.of(anchor);
			}
			if (pinned != null && autoTime) {
				rowErrors.add("Choose a time for this exam when you choose its rooms.");
			}
			String name = optionalText(columns.subjectName(row), SUBJECT_NAME_MAX_LENGTH, Messages.SCHEDULE_BULK_ROW_SUBJECT_NAME_TOO_LONG, rowErrors);
			String code = optionalText(columns.subjectCode(row), SUBJECT_CODE_MAX_LENGTH, Messages.SCHEDULE_BULK_ROW_SUBJECT_CODE_TOO_LONG, rowErrors);
			if (name == null && code == null && rowErrors.isEmpty()) {
				rowErrors.add("Subject name or subject code is required.");
			}
			Integer students = students(columns.text(row, TimetableColumns.Field.STUDENTS), rowErrors);
			RoomCategory roomType = roomType(columns.text(row, TimetableColumns.Field.ROOM_TYPE), roomTypes, rowErrors);
			HallGender gender = gender(columns.text(row, TimetableColumns.Field.GENDER), rowErrors);
			if (!rowErrors.isEmpty()) {
				rowErrors.forEach(message -> errors.add(new BulkUploadErrorDTO(rowNumber, message)));
				continue;
			}
			if (columns.has(TimetableColumns.Field.DAY)) {
				checkDay(columns.text(row, TimetableColumns.Field.DAY), date, rowNumber, warnings);
			}
			String subjectKey = NameNormalizationUtil.normalizeForComparison(code != null ? code : name);
			// The same subject may be listed twice at the same time when one row is for men and one for women.
			String key = date + "@" + (autoTime ? "auto" : times[0] + "-" + times[1]) + "#" + subjectKey + "#" + gender;
			Integer firstRow = seen.putIfAbsent(key, rowNumber);
			if (firstRow != null) {
				errors.add(new BulkUploadErrorDTO(rowNumber, "This subject is already listed at the same date and time in row " + firstRow
					+ ". Put all its students in one row (or one row for Male and one for Female)."));
				continue;
			}
			rows.add(new ExamRow(rowNumber, date, autoTime ? null : times[0], autoTime ? null : times[1], name, code, students, roomType, autoTime, gender,
				pinned == null ? null : List.copyOf(new java.util.LinkedHashSet<>(pinned)), movedTo != null || pinned != null || movedToDay != null,
				anchor != null && adjustments.rooms().get(rowNumber) == null));
		}
		Set<Integer> known = rows.stream().map(ExamRow::row).collect(Collectors.toSet());
		Set<Integer> changedRows = new java.util.TreeSet<>(adjustments.times().keySet());
		changedRows.addAll(adjustments.rooms().keySet());
		changedRows.addAll(adjustments.dates().keySet());
		changedRows.addAll(adjustments.anchors().keySet());
		for (Integer changed : changedRows) {
			if (!known.contains(changed) && errors.stream().noneMatch(error -> error.row() == changed)) {
				errors.add(new BulkUploadErrorDTO(changed, "You changed row " + changed + " on the page, but this file has no exam in that row. Undo your changes."));
			}
		}
		if (!errors.isEmpty()) {
			errors.sort(Comparator.comparingInt(BulkUploadErrorDTO::row));
			throw new BulkRowValidationException(errors.size() + " row(s) in the exam list have problems. Nothing was saved: fix them and upload the file again.", errors);
		}
		return rows;
	}

	private static String optionalText(String text, int maxLength, String tooLongMessage, List<String> errors) {
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

	/** Required whole number from 1 to {@link RoomAllocator#MAX_STUDENTS_PER_EXAM}. */
	private static Integer students(String text, List<String> errors) {
		String normalized = NameNormalizationUtil.normalizeWhitespace(text);
		if (normalized == null || normalized.isBlank()) {
			errors.add("Number of students is required to choose rooms.");
			return null;
		}
		BigDecimal value;
		try {
			value = new BigDecimal(normalized.replace(",", ""));
		} catch (NumberFormatException exception) {
			errors.add("Number of students must be a whole number (found \"" + normalized + "\").");
			return null;
		}
		if (value.stripTrailingZeros().scale() > 0) {
			errors.add("Number of students must be a whole number (found \"" + normalized + "\").");
			return null;
		}
		if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(RoomAllocator.MAX_STUDENTS_PER_EXAM)) > 0) {
			errors.add("Number of students must be between 1 and " + RoomAllocator.MAX_STUDENTS_PER_EXAM + ".");
			return null;
		}
		return value.intValueExact();
	}

	private static final Map<String, HallGender> GENDER_WORDS = new HashMap<>();

	static {
		for (String word : List.of("male", "m", "men", "man", "boys", "boy", "ذكور", "ذكر", "رجال", "بنين")) {
			GENDER_WORDS.put(word, HallGender.MALE);
		}
		for (String word : List.of("female", "f", "women", "woman", "girls", "girl", "إناث", "اناث", "أنثى", "انثى", "بنات", "نساء")) {
			GENDER_WORDS.put(word, HallGender.FEMALE);
		}
	}

	/** "Gender" column: Male / Female; empty or Mixed = any hall. */
	private static HallGender gender(String text, List<String> errors) {
		String normalized = NameNormalizationUtil.normalizeWhitespace(text);
		if (normalized == null || normalized.isBlank() || normalized.equalsIgnoreCase("mixed") || normalized.equals("مختلط")) {
			return null;
		}
		HallGender found = GENDER_WORDS.get(normalized.toLowerCase(Locale.ROOT));
		if (found == null) {
			errors.add("Gender must be Male or Female, or empty for a mixed exam (found \"" + normalized + "\").");
		}
		return found;
	}

	/** "Room Type" column: empty = a normal room; otherwise one of the university's room types. */
	private static RoomCategory roomType(String text, Map<String, RoomCategory> roomTypes, List<String> errors) {
		String normalized = NameNormalizationUtil.normalizeWhitespace(text);
		if (normalized == null || normalized.isBlank()) {
			return null;
		}
		RoomCategory found = roomTypes.get(NameNormalizationUtil.normalizeForComparison(normalized));
		if (found == null) {
			errors.add("Unknown room type \"" + normalized + "\". Room types: "
				+ String.join(", ", roomTypes.values().stream().sorted(RoomCategoryService.ORDER).map(RoomCategory::getName).toList())
				+ ". Leave it empty for a normal room.");
		}
		return found;
	}

	private static final Map<String, DayOfWeek> DAY_NAMES = new HashMap<>();

	static {
		for (DayOfWeek day : DayOfWeek.values()) {
			DAY_NAMES.put(day.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT), day);
			DAY_NAMES.put(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT), day);
		}
		DAY_NAMES.put("السبت", DayOfWeek.SATURDAY);
		DAY_NAMES.put("الأحد", DayOfWeek.SUNDAY);
		DAY_NAMES.put("الاحد", DayOfWeek.SUNDAY);
		DAY_NAMES.put("الاثنين", DayOfWeek.MONDAY);
		DAY_NAMES.put("الإثنين", DayOfWeek.MONDAY);
		DAY_NAMES.put("الثلاثاء", DayOfWeek.TUESDAY);
		DAY_NAMES.put("الأربعاء", DayOfWeek.WEDNESDAY);
		DAY_NAMES.put("الاربعاء", DayOfWeek.WEDNESDAY);
		DAY_NAMES.put("الخميس", DayOfWeek.THURSDAY);
		DAY_NAMES.put("الجمعة", DayOfWeek.FRIDAY);
	}

	/** The date wins; a day name that does not match it is only a warning. Unknown words are ignored. */
	private static void checkDay(String text, LocalDate date, int rowNumber, List<String> warnings) {
		String normalized = NameNormalizationUtil.normalizeWhitespace(text);
		if (normalized == null || normalized.isBlank()) {
			return;
		}
		DayOfWeek written = DAY_NAMES.get(normalized.toLowerCase(Locale.ROOT).replace(".", ""));
		if (written != null && written != date.getDayOfWeek()) {
			warnings.add("Row " + rowNumber + ": the day says " + normalized + " but " + date + " is a "
				+ date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ". The date was used.");
		}
	}

	// ═══════════════════════════════ Planning ═══════════════════════════════

	private record SittingKey(LocalDate date, LocalTime start, LocalTime end) implements Comparable<SittingKey> {
		@Override
		public int compareTo(SittingKey other) {
			return Comparator.comparing(SittingKey::date).thenComparing(SittingKey::start).thenComparing(SittingKey::end).compare(this, other);
		}
	}

	/** @param freeOfType per room type asked for in this sitting: how many rooms of it were free */
	private record PlannedSitting(SittingKey key, TimeSlot slot, int freeRooms, Map<UUID, Integer> freeOfType, List<ExamRow> rows,
		List<RoomAllocator.Placement<Room>> placements, Set<Integer> mixedHalls) {
	}

	private final class Plan {
		final List<PlannedSitting> sittings = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		final List<String> slotsToCreate = new ArrayList<>();
		/** How to fix, per file row. */
		final Map<Integer, List<String>> hints = new HashMap<>();
		List<String> columnsRecognised = List.of();
		/** Rooms already booked outside this plan on the plan's days (for the room view). */
		final List<RoomAllocationPreviewDTO.Booked> booked = new ArrayList<>();
		int exams;

		RoomAllocationPreviewDTO toPreview() {
			int fullySeated = 0;
			int students = 0;
			int unplaced = 0;
			int roomsUsed = 0;
			int seats = 0;
			List<RoomAllocationPreviewDTO.Sitting> sittingDtos = new ArrayList<>();
			sittings.sort(Comparator.comparing(PlannedSitting::key));
			for (PlannedSitting sitting : sittings) {
				List<RoomAllocationPreviewDTO.Exam> examDtos = new ArrayList<>();
				for (RoomAllocator.Placement<Room> placement : sitting.placements()) {
					ExamRow row = sitting.rows().get(Integer.parseInt(placement.exam().key()));
					students += row.students();
					unplaced += placement.unplaced();
					fullySeated += placement.unplaced() == 0 ? 1 : 0;
					roomsUsed += placement.rooms().size();
					seats += placement.seats();
					List<RoomAllocationPreviewDTO.Room> roomDtos = placement.rooms().stream()
						.map(seating -> new RoomAllocationPreviewDTO.Room(seating.room().id().getId(), seating.room().name(), seating.room().capacity(), seating.students()))
						.toList();
					examDtos.add(new RoomAllocationPreviewDTO.Exam(row.row(), row.subjectName(), row.subjectCode(), row.students(),
						row.roomType() == null ? null : row.roomType().getName(),
						row.roomType() == null ? null : sitting.freeOfType().getOrDefault(row.roomType().getId(), 0),
						row.autoTime(), row.edited(), genderLabel(row.gender()), sitting.mixedHalls().contains(Integer.parseInt(placement.exam().key())),
						placement.unplaced(), roomDtos, hints.getOrDefault(row.row(), List.of())));
				}
				examDtos.sort(Comparator.comparingInt(RoomAllocationPreviewDTO.Exam::row));
				SittingKey key = sitting.key();
				sittingDtos.add(new RoomAllocationPreviewDTO.Sitting(
					key.date(),
					key.date().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
					key.start().format(HH_MM) + "–" + key.end().format(HH_MM),
					sitting.slot().getId(),
					sitting.freeRooms(),
					List.copyOf(examDtos)));
			}
			int placed = students - unplaced;
			return new RoomAllocationPreviewDTO(exams, fullySeated, students, unplaced, roomsUsed, seats, seats - placed,
				List.copyOf(sittingDtos), List.copyOf(warnings), List.copyOf(slotsToCreate), columnsRecognised, List.copyOf(booked));
		}
	}

	private Plan plan(MultipartFile file, String dateOrder, String adjustmentsJson) {
		Adjustments adjustments = Adjustments.parse(adjustmentsJson, objectMapper);
		TabularFile.Table table = TabularFile.read(file, MAX_ROWS);
		if (table.rows().isEmpty()) {
			throw new ValidationException(Messages.SCHEDULE_BULK_UPLOAD_EMPTY);
		}
		TimetableColumns columns = TimetableColumns.of(table.headers(), false, false);
		if (!columns.has(TimetableColumns.Field.STUDENTS)) {
			throw new ValidationException("Could not find the number of students column (e.g. \"Students\" or \"Number of Students\").");
		}
		TimetableColumns.DateOrder order = TimetableColumns.parseDateOrder(dateOrder);
		if (order == null) {
			order = TimetableColumns.detectOrder(table.rows().stream()
				.map(row -> columns.cell(row, TimetableColumns.Field.DATE))
				.filter(cell -> cell.date() == null)
				.map(TabularFile.CellValue::text)
				.toList());
		}

		Plan plan = new Plan();
		plan.columnsRecognised = columns.recognised();
		List<ExamRow> rows = readRows(table, columns, order, plan.warnings, adjustments);
		plan.exams = rows.size();

		Map<SittingKey, List<ExamRow>> bySitting = new TreeMap<>();
		for (ExamRow row : rows) {
			if (!row.autoTime()) { // fixed in the file: placed first, exactly at that time
				bySitting.computeIfAbsent(new SittingKey(row.date(), row.start(), row.end()), key -> new ArrayList<>()).add(row);
			}
		}
		List<ExamRow> autoRows = rows.stream().filter(ExamRow::autoTime).toList();

		Map<String, TimeSlot> slotsByTimes = new LinkedHashMap<>();
		for (TimeSlot slot : timeSlotRepository.findByActiveTrueOrderBySortOrderAsc()) {
			slotsByTimes.putIfAbsent(timesKey(slot.getStartTime(), slot.getEndTime()), slot);
		}
		// Out-of-service rooms are never offered. Special types (labs, studios) only go to exams asking for exactly that type.
		List<Room> activeRooms = roomRepository.findAllByActiveTrueOrderByNameAsc();
		if (activeRooms.isEmpty()) {
			throw new ValidationException("There are no rooms to choose from: add rooms, or put rooms back in service.");
		}
		Set<LocalDate> dates = rows.stream().map(ExamRow::date).collect(Collectors.toSet());
		SchedulingContext context = contextLoader.load(dates); // rooms already booked, in every exam period
		for (RoomAssignment existing : context.universe()) { // shown as "Booked" in the room view
			if (existing.getId() != null && existing.getRoom() != null && existing.getTimeSlot() != null) {
				String what = existing.getSubjectCode() != null ? existing.getSubjectCode() : existing.getSubjectName();
				plan.booked.add(new RoomAllocationPreviewDTO.Booked(existing.getExamDate(),
					existing.getTimeSlot().getStartTime().format(HH_MM) + "–" + existing.getTimeSlot().getEndTime().format(HH_MM),
					existing.getRoom().getId(), what == null ? "Booked" : "Booked: " + what));
			}
		}

		for (Map.Entry<SittingKey, List<ExamRow>> entry : bySitting.entrySet()) {
			SittingKey key = entry.getKey();
			TimeSlot slot = slotsByTimes.get(timesKey(key.start(), key.end()));
			if (slot == null) {
				slot = new TimeSlot(); // not saved: only its times are used here
				slot.setLabel("Exam " + key.start().format(HH_MM) + "–" + key.end().format(HH_MM));
				slot.setStartTime(key.start());
				slot.setEndTime(key.end());
				slot.setActive(true);
				slotsByTimes.put(timesKey(key.start(), key.end()), slot);
				plan.slotsToCreate.add(slot.getLabel());
			}

			List<RoomAllocator.RoomOption<Room>> free = freeRooms(activeRooms, context, slot, key.date());
			int freeNormal = (int) free.stream().filter(option -> !option.id().isSpecial()).count();
			List<ExamRow> sittingRows = entry.getValue();
			PlannedSitting sitting = new PlannedSitting(key, slot, freeNormal, new HashMap<>(), sittingRows,
				new ArrayList<>(java.util.Collections.nCopies(sittingRows.size(), (RoomAllocator.Placement<Room>) null)), new java.util.HashSet<>());
			// Rooms the user chose on the page go first, exactly; then the other exams are seated around them.
			List<Integer> rest = new ArrayList<>();
			for (int i = 0; i < sittingRows.size(); i++) {
				ExamRow examRow = sittingRows.get(i);
				if (examRow.pinnedRooms() != null) {
					sitting.placements().set(i, seatPinned(plan, examRow, i, free, activeRooms, key));
				} else {
					rest.add(i);
				}
			}
			seatSitting(sitting, rest, free);
			for (int i = 0; i < sittingRows.size(); i++) {
				for (RoomAllocator.Seating<Room> seating : sitting.placements().get(i).rooms()) {
					context.add(booking(seating.room().id(), slot, key.date())); // later overlapping sittings see it as taken
				}
				warn(plan, sittingRows.get(i), sitting.placements().get(i), sitting.mixedHalls().contains(i), key, false);
			}
			plan.sittings.add(sitting);
		}
		if (!autoRows.isEmpty()) {
			chooseTimes(plan, autoRows, activeRooms, context);
		}
		addHints(plan, activeRooms, context);
		return plan;
	}

	// ═══════════════════════════════ Seating (room type + hall gender) ═══════════════════════════════

	/**
	 * An exam whose rooms the user chose: exactly those rooms, in that order, as far as they are free. A room that
	 * is booked meanwhile, out of service or not needed is left out with a warning.
	 */
	private static RoomAllocator.Placement<Room> seatPinned(Plan plan, ExamRow row, int index, List<RoomAllocator.RoomOption<Room>> free,
	                                                       List<Room> activeRooms, SittingKey key) {
		List<RoomAllocator.Seating<Room>> seats = new ArrayList<>();
		int left = row.students();
		for (UUID roomId : row.pinnedRooms()) {
			RoomAllocator.RoomOption<Room> option = free.stream().filter(candidate -> candidate.id().getId().equals(roomId)).findFirst().orElse(null);
			if (option == null) {
				String name = activeRooms.stream().filter(room -> room.getId().equals(roomId)).map(Room::getName).findFirst().orElse(null);
				plan.warnings.add("Row " + row.row() + ": " + (name == null
					? "a room you chose is not in service any more, so it was left out."
					: "room " + name + " is not free at " + key.start().format(HH_MM) + "–" + key.end().format(HH_MM) + " on " + key.date()
						+ " (another exam has it), so it was left out."));
				continue;
			}
			if (!usable(row, option.id(), false)) { // the server checks the room type and hall gender again: never trust the page
				Room room = option.id();
				plan.warnings.add("Row " + row.row() + ": room " + option.name() + (typeFits(row.roomType(), room)
					? " is a " + genderLabel(hallOf(room)) + " hall and this exam is " + genderLabel(row.gender())
					: (row.roomType() == null
						? " is a “" + room.getCategory().getName() + "” room, only for exams that ask for it"
						: " is not a “" + row.roomType().getName() + "” room"))
					+ ", so it was left out.");
				continue;
			}
			if (left <= 0) {
				plan.warnings.add("Row " + row.row() + ": room " + option.name() + " is not needed (the other rooms you chose seat everyone), so it was left free.");
				continue;
			}
			int here = Math.min(left, option.capacity());
			seats.add(new RoomAllocator.Seating<>(option, here));
			left -= here;
			free.remove(option);
		}
		if (row.fillMore() && left > 0) {
			// Dropped on a room that is too small: add rooms of the right kind, the same building first.
			String building = seats.isEmpty() ? "" : seats.get(0).room().group();
			List<RoomAllocator.RoomOption<Room>> usableRooms = free.stream().filter(option -> usable(row, option.id(), false)).toList();
			List<RoomAllocator.RoomOption<Room>> sameBuilding = usableRooms.stream().filter(option -> option.group().equals(building)).toList();
			List<RoomAllocator.RoomOption<Room>> pool = !building.isEmpty() && sameBuilding.stream().mapToInt(RoomAllocator.RoomOption::capacity).sum() >= left
				? sameBuilding : usableRooms;
			RoomAllocator.Placement<Room> more = RoomAllocator.allocate(List.of(new RoomAllocator.Exam("more", left)), pool).get(0);
			for (RoomAllocator.Seating<Room> seating : more.rooms()) {
				seats.add(seating);
				free.remove(seating.room());
			}
			left = more.unplaced();
		}
		return new RoomAllocator.Placement<>(new RoomAllocator.Exam(Integer.toString(index), row.students()), List.copyOf(seats), left);
	}

	/** One exam's rooms; {@code mixedHalls} = a Male/Female exam that needed Mixed halls. */
	private record Seated(RoomAllocator.Placement<Room> placement, boolean mixedHalls) {
	}

	private static HallGender hallOf(Room room) {
		return room.getHallGender() == null ? HallGender.MIXED : room.getHallGender();
	}

	/** Exactly the room type asked for; no type = normal (not special) rooms. */
	private static boolean typeFits(RoomCategory type, Room room) {
		if (type == null) {
			return !room.isSpecial();
		}
		return room.getCategory() != null && type.getId().equals(room.getCategory().getId());
	}

	/**
	 * Seats the exams of one sitting in {@code free} (used rooms are removed from it). Groups with stricter
	 * needs go first: a special room type before normal rooms, a Male/Female exam before a mixed one.
	 */
	private static void seatSitting(PlannedSitting sitting, List<Integer> indexes, List<RoomAllocator.RoomOption<Room>> free) {
		record Group(RoomCategory type, HallGender gender) {
		}
		Map<UUID, RoomCategory> types = new HashMap<>();
		Map<String, Group> groups = new LinkedHashMap<>();
		Map<String, List<Integer>> members = new LinkedHashMap<>();
		for (int index : indexes) {
			ExamRow row = sitting.rows().get(index);
			String key = (row.roomType() == null ? "-" : row.roomType().getId().toString()) + "/" + row.gender();
			groups.putIfAbsent(key, new Group(row.roomType(), row.gender()));
			members.computeIfAbsent(key, k -> new ArrayList<>()).add(index);
			if (row.roomType() != null) {
				types.put(row.roomType().getId(), row.roomType());
			}
		}
		for (RoomCategory type : types.values()) {
			sitting.freeOfType().putIfAbsent(type.getId(), (int) free.stream().filter(option -> typeFits(type, option.id())).count());
		}
		List<String> order = new ArrayList<>(groups.keySet());
		order.sort(Comparator.comparing((String key) -> groups.get(key).type() == null)
			.thenComparing(key -> groups.get(key).type() == null ? "" : String.format("%05d", groups.get(key).type().getSortOrder()) + groups.get(key).type().getName())
			.thenComparing(key -> groups.get(key).gender() == null));
		for (String key : order) {
			Group group = groups.get(key);
			Map<Integer, Seated> seated = seatGroup(sitting.rows(), members.get(key), group.type(), group.gender(), free);
			seated.forEach((index, result) -> {
				sitting.placements().set(index, result.placement());
				if (result.mixedHalls()) {
					sitting.mixedHalls().add(index);
				}
			});
		}
	}

	/**
	 * Seats exams of the same room type and gender. Male/Female: halls of that gender first, then Mixed halls.
	 * No gender: Mixed halls first (gendered halls stay free for gendered exams), then any hall.
	 */
	private static Map<Integer, Seated> seatGroup(List<ExamRow> rows, List<Integer> indexes, RoomCategory type, HallGender gender,
	                                              List<RoomAllocator.RoomOption<Room>> free) {
		java.util.function.Predicate<Room> first = gender != null ? room -> hallOf(room) == gender : room -> hallOf(room) == HallGender.MIXED;
		java.util.function.Predicate<Room> second = gender != null ? room -> hallOf(room) == HallGender.MIXED : room -> hallOf(room) != HallGender.MIXED;

		List<RoomAllocator.Exam> exams = new ArrayList<>();
		for (int index : indexes) {
			exams.add(new RoomAllocator.Exam(Integer.toString(index), rows.get(index).students()));
		}
		List<RoomAllocator.RoomOption<Room>> firstPool = free.stream().filter(option -> typeFits(type, option.id()) && first.test(option.id())).toList();
		Map<Integer, RoomAllocator.Placement<Room>> firstResult = new LinkedHashMap<>();
		List<RoomAllocator.Exam> remainders = new ArrayList<>();
		for (RoomAllocator.Placement<Room> placement : RoomAllocator.allocate(exams, firstPool)) {
			firstResult.put(Integer.parseInt(placement.exam().key()), placement);
			placement.rooms().forEach(seating -> free.remove(seating.room()));
			if (placement.unplaced() > 0) {
				remainders.add(new RoomAllocator.Exam(placement.exam().key(), placement.unplaced()));
			}
		}
		Map<Integer, RoomAllocator.Placement<Room>> secondResult = new HashMap<>();
		if (!remainders.isEmpty()) {
			List<RoomAllocator.RoomOption<Room>> secondPool = free.stream().filter(option -> typeFits(type, option.id()) && second.test(option.id())).toList();
			for (RoomAllocator.Placement<Room> placement : RoomAllocator.allocate(remainders, secondPool)) {
				secondResult.put(Integer.parseInt(placement.exam().key()), placement);
				placement.rooms().forEach(seating -> free.remove(seating.room()));
			}
		}
		Map<Integer, Seated> result = new LinkedHashMap<>();
		firstResult.forEach((index, placement) -> {
			RoomAllocator.Placement<Room> extra = secondResult.get(index);
			if (extra == null) {
				result.put(index, new Seated(placement, false));
				return;
			}
			List<RoomAllocator.Seating<Room>> rooms = new ArrayList<>(placement.rooms());
			rooms.addAll(extra.rooms());
			RoomAllocator.Exam exam = new RoomAllocator.Exam(Integer.toString(index), rows.get(index).students());
			result.put(index, new Seated(new RoomAllocator.Placement<>(exam, List.copyOf(rooms), extra.unplaced()),
				gender != null && !extra.rooms().isEmpty()));
		});
		return result;
	}

	private static String genderLabel(HallGender gender) {
		return gender == null ? null : gender == HallGender.FEMALE ? "Female" : "Male";
	}

	/** Warnings for one seated exam (no seat; Mixed halls used by a Male/Female exam). */
	private static void warn(Plan plan, ExamRow row, RoomAllocator.Placement<Room> placement, boolean mixedHalls, SittingKey key, boolean chosen) {
		String time = key.start().format(HH_MM) + "–" + key.end().format(HH_MM);
		String kind = (row.gender() == null ? "" : genderLabel(row.gender()) + " or Mixed ")
			+ (row.roomType() == null ? "rooms" : "\"" + row.roomType().getName() + "\" rooms");
		if (placement.unplaced() > 0) {
			plan.warnings.add(chosen
				? "Row " + row.row() + ": no time slot on " + key.date() + " has enough free " + kind + " for " + row.students()
					+ " students. Placed at " + time + ": " + placement.unplaced() + " students have no seat."
				: "Row " + row.row() + ": not enough free " + kind + " on " + key.date() + " " + time + ": "
					+ placement.unplaced() + " of " + row.students() + " students have no seat.");
		} else if (mixedHalls) {
			int inMixed = placement.rooms().stream().filter(seating -> hallOf(seating.room().id()) == HallGender.MIXED).mapToInt(RoomAllocator.Seating::students).sum();
			plan.warnings.add("Row " + row.row() + ": not enough free " + genderLabel(row.gender()) + " halls on " + key.date() + " " + time
				+ ": " + inMixed + " students placed in Mixed halls.");
		}
	}

	// ═══════════════════════════════ How to fix (hints) ═══════════════════════════════

	private static final java.time.format.DateTimeFormatter DAY_MONTH = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

	/** Rooms this exam may use: its room type, and halls of its gender or Mixed (any hall when no gender). */
	private static boolean usable(ExamRow row, Room room, boolean ownGenderOnly) {
		if (!typeFits(row.roomType(), room)) {
			return false;
		}
		if (row.gender() == null) {
			return true;
		}
		return hallOf(room) == row.gender() || (!ownGenderOnly && hallOf(room) == HallGender.MIXED);
	}

	private static String kind(ExamRow row, boolean ownGenderOnly) {
		String type = row.roomType() == null ? "" : "“" + row.roomType().getName() + "” ";
		if (row.gender() != null && ownGenderOnly) {
			return type + genderLabel(row.gender()) + " halls";
		}
		return type + "rooms";
	}

	/** The exam asks for a room type whose rooms together cannot seat it (or that has no rooms at all). */
	private static boolean typeTooSmall(ExamRow row, List<Room> activeRooms) {
		return row.roomType() != null
			&& activeRooms.stream().filter(room -> typeFits(row.roomType(), room)).mapToInt(Room::getCapacity).sum() < row.students();
	}

	private static String names(List<Room> rooms, int max) {
		return String.join(", ", rooms.stream().limit(max).map(room -> room.getName() + " (" + room.getCapacity() + ")").toList())
			+ (rooms.size() > max ? " …" : "");
	}

	/**
	 * Concrete suggestions for every exam with students without a seat, or a Male/Female exam that needed Mixed
	 * halls. Computed from the university's own rooms, times and this plan (after everything is placed).
	 */
	private void addHints(Plan plan, List<Room> activeRooms, SchedulingContext context) {
		List<TimeSlot> slots = new ArrayList<>(timeSlotRepository.findByActiveTrueOrderBySortOrderAsc().stream()
			.collect(Collectors.toMap(slot -> timesKey(slot.getStartTime(), slot.getEndTime()), slot -> slot, (a, b) -> a, LinkedHashMap::new))
			.values());
		slots.sort(Comparator.comparing(TimeSlot::getStartTime).thenComparing(TimeSlot::getEndTime));
		List<Room> inactive = roomRepository.findAll().stream().filter(room -> !room.isActive()).sorted(Comparator.comparing(Room::getName)).toList();
		Map<String, List<RoomAllocator.RoomOption<Room>>> freeCache = new HashMap<>();

		for (PlannedSitting sitting : plan.sittings) {
			for (int i = 0; i < sitting.rows().size(); i++) {
				RoomAllocator.Placement<Room> placement = sitting.placements().get(i);
				ExamRow row = sitting.rows().get(i);
				boolean mixed = sitting.mixedHalls().contains(i);
				if (placement == null || (placement.unplaced() == 0 && !mixed)) {
					continue;
				}
				boolean ownOnly = placement.unplaced() == 0 && mixed; // seated, but partly in Mixed halls
				String kind = kind(row, ownOnly);
				String day = row.date().format(DAY_MONTH);
				String here = sitting.key().start().format(HH_MM) + "–" + sitting.key().end().format(HH_MM);
				List<String> hints = new ArrayList<>();

				// 1. Another time that day with enough free rooms for the whole exam.
				List<String> better = new ArrayList<>();
				for (TimeSlot slot : slots) {
					if (slot.getStartTime().equals(sitting.key().start()) && slot.getEndTime().equals(sitting.key().end())) {
						continue;
					}
					List<RoomAllocator.RoomOption<Room>> free = freeCache.computeIfAbsent(row.date() + "@" + timesKey(slot.getStartTime(), slot.getEndTime()),
						key -> freeRooms(activeRooms, context, slot, row.date()));
					List<Room> fitting = free.stream().map(RoomAllocator.RoomOption::id).filter(room -> usable(row, room, ownOnly)).toList();
					int seats = fitting.stream().mapToInt(Room::getCapacity).sum();
					if (seats >= row.students()) {
						better.add(slot.getStartTime().format(HH_MM) + "–" + slot.getEndTime().format(HH_MM) + " (" + fitting.size() + " free " + kind + ", " + seats + " seats)");
					}
				}
				if (!better.isEmpty()) {
					hints.add("Move it to another time on " + day + ": " + String.join(" or ", better.stream().limit(2).toList())
						+ " has enough for all " + row.students() + " students. Change the time in your file"
						+ (row.autoTime() ? "." : ", or leave it empty so Uni-Guard chooses."));
				} else if (!ownOnly && !typeTooSmall(row, activeRooms)) { // a type without enough rooms has its own hint below
					hints.add("No time on " + day + " has enough free " + kind + " for " + row.students() + " students: give it another date in your file, "
						+ "or split it into two rows (for example two groups at different times).");
				}

				// 2. The room type: no rooms at all, or too few seats in total.
				if (row.roomType() != null) {
					List<Room> ofType = activeRooms.stream().filter(room -> typeFits(row.roomType(), room)).toList();
					int typeSeats = ofType.stream().mapToInt(Room::getCapacity).sum();
					// Example rooms only for lab types (a lab is a fair guess for "Computer Lab"; not for "Drawing Studio").
					boolean labType = RoomNames.looksLikeLab(row.roomType().getName());
					List<Room> candidates = !labType ? List.of() : activeRooms.stream()
						.filter(room -> !typeFits(row.roomType(), room) && (room.isSpecial() || RoomNames.looksLikeLab(room.getName())))
						.sorted(Comparator.comparing(Room::getName)).toList();
					if (ofType.isEmpty()) {
						hints.add("No room has the type “" + row.roomType().getName() + "”. On the Rooms page give this type to the right rooms"
							+ (candidates.isEmpty() ? "" : " (for example " + names(candidates, 3) + ")") + ", then choose rooms again.");
					} else if (typeSeats < row.students()) {
						hints.add("All “" + row.roomType().getName() + "” rooms together have only " + typeSeats + " seats for " + row.students()
							+ " students: give this type to more rooms (Rooms page), or split the exam into two rows at different times.");
					}
				}

				// 3. A smaller exam at the same time that fits elsewhere and frees rooms this exam can use.
				if (placement.unplaced() > 0) {
					String move = null;
					int moveStudents = Integer.MAX_VALUE;
					for (int j = 0; j < sitting.rows().size(); j++) {
						ExamRow other = sitting.rows().get(j);
						RoomAllocator.Placement<Room> otherPlacement = sitting.placements().get(j);
						if (j == i || otherPlacement == null || otherPlacement.rooms().isEmpty() || subjectKey(other).equals(subjectKey(row))) {
							continue;
						}
						int freed = otherPlacement.rooms().stream().filter(seating -> usable(row, seating.room().id(), false))
							.mapToInt(seating -> seating.room().capacity()).sum();
						if (freed < placement.unplaced() || other.students() >= moveStudents) {
							continue;
						}
						for (TimeSlot slot : slots) {
							if (slot.getStartTime().equals(sitting.key().start()) && slot.getEndTime().equals(sitting.key().end())) {
								continue;
							}
							List<RoomAllocator.RoomOption<Room>> free = freeCache.computeIfAbsent(row.date() + "@" + timesKey(slot.getStartTime(), slot.getEndTime()),
								key -> freeRooms(activeRooms, context, slot, row.date()));
							int seats = free.stream().map(RoomAllocator.RoomOption::id).filter(room -> usable(other, room, false)).mapToInt(Room::getCapacity).sum();
							if (seats >= other.students()) {
								move = (other.subjectCode() != null ? other.subjectCode() : other.subjectName()) + " (" + other.students()
									+ " students, row " + other.row() + ") to " + slot.getStartTime().format(HH_MM) + "–" + slot.getEndTime().format(HH_MM);
								moveStudents = other.students();
								break;
							}
						}
					}
					if (move != null) {
						hints.add("Or move " + move + ": it fits there, and its rooms at " + here + " can then take the "
							+ placement.unplaced() + " students without a seat.");
					}
				}

				// 4. Rooms out of service that this exam could use.
				List<Room> backInService = inactive.stream().filter(room -> usable(row, room, ownOnly)).toList();
				if (!backInService.isEmpty()) {
					hints.add("Out of service now: " + names(backInService, 3) + ". Putting them back in service (Rooms page) adds "
						+ backInService.stream().mapToInt(Room::getCapacity).sum() + " seats.");
				}

				// 5. Gender: too few halls of that gender in the whole university.
				if (row.gender() != null) {
					int ownSeats = activeRooms.stream().filter(room -> typeFits(row.roomType(), room) && hallOf(room) == row.gender()).mapToInt(Room::getCapacity).sum();
					if (ownSeats < row.students()) {
						hints.add("Only " + ownSeats + " seats are in " + genderLabel(row.gender()) + " halls" + (row.roomType() == null ? "" : " of this type")
							+ " for " + row.students() + " students: mark more halls as " + genderLabel(row.gender()) + " on the Rooms page.");
					}
				}
				plan.hints.put(row.row(), List.copyOf(hints));
			}
		}
	}

	/**
	 * After Create schedule: for each time with empty seats, why staff are missing (not working that weekday,
	 * on leave, gendered halls) and what to do.
	 */
	private List<String> staffHints(List<RoomAssignment> staffed) {
		record Gap(LocalDate date, TimeSlot slot) {
		}
		Map<String, Gap> gaps = new TreeMap<>();
		Map<String, int[]> empty = new HashMap<>(); // {invigilator seats, chief seats, female-hall seats, male-hall seats}
		for (RoomAssignment assignment : staffed) {
			int invigilators = (int) assignment.getInvigilatorAssignments().stream().filter(seat -> seat.isRequired() && seat.getInvigilator() == null).count();
			int chief = assignment.getChiefInvigilator() == null ? 1 : 0;
			if (invigilators == 0 && chief == 0) {
				continue;
			}
			String key = assignment.getExamDate() + "@" + assignment.getTimeSlot().getStartTime() + "-" + assignment.getTimeSlot().getEndTime();
			gaps.putIfAbsent(key, new Gap(assignment.getExamDate(), assignment.getTimeSlot()));
			int[] counts = empty.computeIfAbsent(key, k -> new int[4]);
			counts[0] += invigilators;
			counts[1] += chief;
			HallGender hall = hallOf(assignment.getRoom());
			if (hall == HallGender.FEMALE) {
				counts[2] += invigilators + chief;
			} else if (hall == HallGender.MALE) {
				counts[3] += invigilators + chief;
			}
		}
		if (gaps.isEmpty()) {
			return List.of();
		}
		List<Person> invigilators = personRepository.findAllByRoleAndActiveTrue(PersonRole.INVIGILATOR);
		List<Person> chiefs = personRepository.findAllByRoleAndActiveTrue(PersonRole.CHIEF_INVIGILATOR);
		SchedulingContext context = contextLoader.load(gaps.values().stream().map(Gap::date).collect(Collectors.toSet()));
		List<String> hints = new ArrayList<>();
		for (Map.Entry<String, Gap> entry : gaps.entrySet()) {
			Gap gap = entry.getValue();
			int[] counts = empty.get(entry.getKey());
			String weekday = gap.date().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
			List<Person> pool = counts[0] > 0 ? invigilators : chiefs;
			String role = counts[0] > 0 ? "invigilators" : "chiefs";
			long notWorking = pool.stream().filter(person -> !StaffConcurrencyRules.isAvailable(person, gap.date())).count();
			long onLeave = pool.stream().filter(person -> StaffConcurrencyRules.isAvailable(person, gap.date()) && context.leaveOn(person.getId(), gap.date()).isPresent()).count();
			StringBuilder hint = new StringBuilder();
			hint.append(gap.date().format(DAY_MONTH)).append(' ').append(StaffConcurrencyRules.describeSlot(gap.slot())).append(": ");
			List<String> missing = new ArrayList<>();
			if (counts[0] > 0) {
				missing.add(counts[0] + " invigilator seat" + (counts[0] == 1 ? "" : "s"));
			}
			if (counts[1] > 0) {
				missing.add(counts[1] + " chief seat" + (counts[1] == 1 ? "" : "s"));
			}
			hint.append(String.join(" and ", missing)).append(" empty. Of ").append(pool.size()).append(' ').append(role).append(", ")
				.append(notWorking).append(" don't work on ").append(weekday).append(onLeave > 0 ? " and " + onLeave + " are on leave" : "")
				.append(". Add ").append(weekday).append(" to some of them (People page), add staff, or move an exam from this time to a quieter one.");
			if (counts[2] > 0) {
				hint.append(' ').append(counts[2]).append(" of the empty seats are in Female halls: only female staff can sit there.");
			}
			if (counts[3] > 0) {
				hint.append(' ').append(counts[3]).append(" of the empty seats are in Male halls: only male staff can sit there.");
			}
			hints.add(hint.toString());
		}
		return hints;
	}

	// ═══════════════════════════════ Automatic time slots ═══════════════════════════════

	/** What a sitting needs from the staff: {rooms, female rooms, male rooms, invigilators, female inv., male inv.} + students. */
	private record Demand(int[] staff, int students) {
	}

	/**
	 * Exams whose time slot is empty in the file: the system picks one of the university's time slots on that
	 * date. Fixed exams are already placed. Hardest first (special room
	 * type, then a gender, then most students). Rank per slot: rooms fit, staff fit (also by gender for gendered
	 * halls), own-gender halls enough, then the least busy slot (spread over the day). When nothing fits, the
	 * exam goes where most students get a seat and is reported.
	 */
	private void chooseTimes(Plan plan, List<ExamRow> autoRows, List<Room> activeRooms, SchedulingContext context) {
		List<TimeSlot> candidates = new ArrayList<>(timeSlotRepository.findByActiveTrueOrderBySortOrderAsc().stream()
			.collect(Collectors.toMap(slot -> timesKey(slot.getStartTime(), slot.getEndTime()), slot -> slot, (a, b) -> a, LinkedHashMap::new))
			.values());
		candidates.sort(Comparator.comparing(TimeSlot::getStartTime).thenComparing(TimeSlot::getEndTime));
		if (candidates.isEmpty()) {
			throw new ValidationException("Some exams have no time slot, and there are no time slots to choose from. Add time slots first (Time Slots page), or fill in the time.");
		}
		SchedulingSettings settings = context.settings();
		List<Person> chiefs = personRepository.findAllByRoleAndActiveTrue(PersonRole.CHIEF_INVIGILATOR);
		List<Person> invigilators = personRepository.findAllByRoleAndActiveTrue(PersonRole.INVIGILATOR);
		Map<String, int[]> staffCapacity = new HashMap<>(); // date@slot → same layout as Demand.staff

		List<ExamRow> order = new ArrayList<>(autoRows);
		order.sort(Comparator.comparing((ExamRow row) -> row.roomType() == null)
			.thenComparing(row -> row.gender() == null)
			.thenComparing(Comparator.comparingInt(ExamRow::students).reversed())
			.thenComparingInt(ExamRow::row));

		// Rows of the same subject on the same date (e.g. its Male and Female rows) always get the same time.
		Map<String, List<ExamRow>> groups = new LinkedHashMap<>();
		for (ExamRow row : order) {
			groups.computeIfAbsent(row.date() + "#" + subjectKey(row), key -> new ArrayList<>()).add(row);
		}

		for (List<ExamRow> members : groups.values()) {
			ExamRow lead = members.get(0);
			// If the file already fixes a time for this subject on that date, its other rows follow it.
			List<TimeSlot> choices = candidates;
			for (PlannedSitting sitting : plan.sittings) {
				if (sitting.key().date().equals(lead.date()) && sitting.rows().stream().anyMatch(other -> !other.autoTime() && subjectKey(other).equals(subjectKey(lead)))) {
					choices = List.of(sitting.slot());
					break;
				}
			}

			TimeSlot best = null;
			List<Seated> bestSeated = null;
			int bestNormal = 0;
			Map<UUID, Integer> bestOfType = null;
			long[] bestRank = null;
			boolean bestStaffOk = false;
			for (TimeSlot slot : choices) {
				List<RoomAllocator.RoomOption<Room>> free = new ArrayList<>(freeRooms(activeRooms, context, slot, lead.date()));
				int normal = (int) free.stream().filter(option -> !option.id().isSpecial()).count();
				Map<UUID, Integer> ofType = new HashMap<>();
				for (ExamRow member : members) {
					if (member.roomType() != null) {
						ofType.putIfAbsent(member.roomType().getId(), (int) free.stream().filter(option -> typeFits(member.roomType(), option.id())).count());
					}
				}
				PlannedSitting existing = findSitting(plan, lead.date(), slot);
				List<ExamRow> trialRows = new ArrayList<>(existing == null ? List.of() : existing.rows());
				List<Seated> seated = new ArrayList<>();
				int[] need = new int[6];
				boolean roomsOk = true;
				boolean mixed = false;
				int unplaced = 0;
				for (ExamRow member : members) { // the free rooms shrink as each row is seated
					int index = trialRows.size();
					trialRows.add(member);
					Seated result = seatGroup(trialRows, List.of(index), member.roomType(), member.gender(), free).get(index);
					seated.add(result);
					roomsOk &= result.placement().unplaced() == 0;
					mixed |= result.mixedHalls();
					unplaced += result.placement().unplaced();
					int[] memberNeed = staffNeed(result.placement(), settings);
					for (int i = 0; i < need.length; i++) {
						need[i] += memberNeed[i];
					}
				}

				Demand already = demand(plan, lead.date(), slot, settings);
				int[] capacity = staffCapacity.computeIfAbsent(lead.date() + "@" + slot.getId() + "@" + slot.getStartTime(),
					key -> freeStaff(chiefs, invigilators, context, settings, lead.date(), slot));
				boolean staffOk = true;
				for (int i = 0; i < need.length; i++) {
					staffOk &= capacity[i] >= already.staff()[i] + need[i];
				}

				long[] rank = {
					roomsOk ? 0 : 1,
					staffOk ? 0 : 1,
					mixed ? 1 : 0,
					roomsOk ? already.students() : unplaced,
					slot.getStartTime().toSecondOfDay()
				};
				if (bestRank == null || java.util.Arrays.compare(rank, bestRank) < 0) {
					bestRank = rank;
					best = slot;
					bestSeated = seated;
					bestNormal = normal;
					bestOfType = ofType;
					bestStaffOk = staffOk;
				}
			}

			PlannedSitting sitting = findSitting(plan, lead.date(), best);
			if (sitting == null) {
				sitting = new PlannedSitting(new SittingKey(lead.date(), best.getStartTime(), best.getEndTime()), best, bestNormal,
					new HashMap<>(), new ArrayList<>(), new ArrayList<>(), new java.util.HashSet<>());
				plan.sittings.add(sitting);
			}
			for (int m = 0; m < members.size(); m++) {
				ExamRow row = members.get(m);
				Seated seated = bestSeated.get(m);
				int index = sitting.rows().size();
				sitting.rows().add(row);
				sitting.placements().add(seated.placement());
				if (seated.mixedHalls()) {
					sitting.mixedHalls().add(index);
				}
				for (RoomAllocator.Seating<Room> seating : seated.placement().rooms()) {
					context.add(booking(seating.room().id(), best, row.date()));
				}
				warn(plan, row, seated.placement(), seated.mixedHalls(), sitting.key(), true);
				if (seated.placement().unplaced() == 0 && !bestStaffOk) {
					String time = best.getStartTime().format(HH_MM) + "–" + best.getEndTime().format(HH_MM);
					plan.warnings.add("Row " + row.row() + ": time " + time + " chosen on " + row.date()
						+ ", but no time that day has enough free chiefs and invigilators for it"
						+ (row.gender() == null ? "" : " (" + genderLabel(row.gender()) + " halls need " + genderLabel(row.gender()).toLowerCase(Locale.ROOT) + " staff)")
						+ ". Check the staff after Create schedule.");
				}
			}
			bestOfType.forEach(sitting.freeOfType()::putIfAbsent);
		}
	}

	/** A subject is its code, or its name when there is no code (as the duplicate check in the file). */
	private static String subjectKey(ExamRow row) {
		return NameNormalizationUtil.normalizeForComparison(row.subjectCode() != null ? row.subjectCode() : row.subjectName());
	}

	/** Active rooms not booked at an overlapping time on that date (bookings of this plan included). */
	private static List<RoomAllocator.RoomOption<Room>> freeRooms(List<Room> activeRooms, SchedulingContext context, TimeSlot slot, LocalDate date) {
		List<RoomAllocator.RoomOption<Room>> free = new ArrayList<>();
		for (Room room : activeRooms) {
			if (StaffConcurrencyRules.findRoomOverlap(context.universe(), booking(room, slot, date)).isEmpty()) {
				free.add(new RoomAllocator.RoomOption<>(room, room.getName(), room.getCapacity(), room.buildingKey()));
			}
		}
		return free;
	}

	private static PlannedSitting findSitting(Plan plan, LocalDate date, TimeSlot slot) {
		for (PlannedSitting sitting : plan.sittings) {
			if (sitting.key().date().equals(date) && sitting.key().start().equals(slot.getStartTime()) && sitting.key().end().equals(slot.getEndTime())) {
				return sitting;
			}
		}
		return null;
	}

	/** Staff one placement needs: {rooms, female rooms, male rooms, invigilators, female inv., male inv.}. */
	private static int[] staffNeed(RoomAllocator.Placement<Room> placement, SchedulingSettings settings) {
		int[] need = new int[6];
		for (RoomAllocator.Seating<Room> seating : placement.rooms()) {
			Room room = seating.room().id();
			int invigilators = settings.requiredInvigilators(room, seating.students());
			need[0]++;
			need[3] += invigilators;
			if (hallOf(room) == HallGender.FEMALE) {
				need[1]++;
				need[4] += invigilators;
			} else if (hallOf(room) == HallGender.MALE) {
				need[2]++;
				need[5] += invigilators;
			}
		}
		return need;
	}

	/** What this plan already puts at an overlapping time on that date. */
	private static Demand demand(Plan plan, LocalDate date, TimeSlot slot, SchedulingSettings settings) {
		int[] staff = new int[6];
		int students = 0;
		for (PlannedSitting sitting : plan.sittings) {
			if (!sitting.key().date().equals(date) || !StaffConcurrencyRules.overlaps(sitting.slot(), slot)) {
				continue;
			}
			for (RoomAllocator.Placement<Room> placement : sitting.placements()) {
				if (placement == null) {
					continue;
				}
				students += placement.exam().students() - placement.unplaced();
				int[] need = staffNeed(placement, settings);
				for (int i = 0; i < need.length; i++) {
					staff[i] += need[i];
				}
			}
		}
		return new Demand(staff, students);
	}

	/**
	 * Staff free at that time: active, working that weekday, not on leave, not already on duty at an overlapping
	 * time (any exam period). Same layout as {@link #staffNeed}: chief rooms (a chief may cover several rooms) and
	 * invigilators, in total and by gender (gendered halls need staff of that gender). An estimate only;
	 * Create schedule applies every rule exactly.
	 */
	private static int[] freeStaff(List<Person> chiefs, List<Person> invigilators, SchedulingContext context, SchedulingSettings settings,
	                               LocalDate date, TimeSlot slot) {
		Set<UUID> busy = new java.util.HashSet<>();
		for (RoomAssignment assignment : context.universe()) {
			if (!date.equals(assignment.getExamDate()) || !StaffConcurrencyRules.overlaps(assignment.getTimeSlot(), slot)) {
				continue;
			}
			if (assignment.getChiefInvigilator() != null) {
				busy.add(assignment.getChiefInvigilator().getId());
			}
			for (var seat : assignment.getInvigilatorAssignments()) {
				if (seat.getInvigilator() != null) {
					busy.add(seat.getInvigilator().getId());
				}
			}
		}
		int[] capacity = new int[6];
		for (Person chief : chiefs) {
			if (isFree(chief, busy, context, date)) {
				int rooms = settings.parallelRoomsFor(chief);
				capacity[0] += rooms;
				capacity[1] += isGender(chief, "FEMALE") ? rooms : 0;
				capacity[2] += isGender(chief, "MALE") ? rooms : 0;
			}
		}
		for (Person invigilator : invigilators) {
			if (isFree(invigilator, busy, context, date)) {
				capacity[3]++;
				capacity[4] += isGender(invigilator, "FEMALE") ? 1 : 0;
				capacity[5] += isGender(invigilator, "MALE") ? 1 : 0;
			}
		}
		return capacity;
	}

	private static boolean isGender(Person person, String gender) {
		return person.getGender() != null && person.getGender().name().equals(gender);
	}

	private static boolean isFree(Person person, Set<UUID> busy, SchedulingContext context, LocalDate date) {
		return !busy.contains(person.getId())
			&& StaffConcurrencyRules.isAvailable(person, date)
			&& context.leaveOn(person.getId(), date).isEmpty();
	}

	private static RoomAssignment booking(Room room, TimeSlot slot, LocalDate date) {
		RoomAssignment probe = new RoomAssignment();
		probe.setRoom(room);
		probe.setTimeSlot(slot);
		probe.setExamDate(date);
		return probe;
	}

	private static String timesKey(LocalTime start, LocalTime end) {
		return start.format(HH_MM) + "-" + end.format(HH_MM);
	}
}
