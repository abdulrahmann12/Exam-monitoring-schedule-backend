package schedule.example.schedule.service;

import jakarta.persistence.EntityManager;
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
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import schedule.example.schedule.dto.bulk.BulkDuplicateStrategy;
import schedule.example.schedule.dto.bulk.BulkUploadErrorDTO;
import schedule.example.schedule.dto.bulk.BulkUploadResultDTO;
import schedule.example.schedule.entity.Person;
import schedule.example.schedule.entity.Room;
import schedule.example.schedule.entity.enums.PersonRole;
import schedule.example.schedule.entity.enums.RoomType;
import schedule.example.schedule.entity.enums.WeekDay;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.repository.PersonRepository;
import schedule.example.schedule.repository.RoomRepository;
import schedule.example.schedule.util.DuplicateResolverUtil;
import schedule.example.schedule.util.ExcelParserUtil;
import schedule.example.schedule.util.NameNormalizationUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class BulkUploadService {

	private static final Logger LOGGER = LoggerFactory.getLogger(BulkUploadService.class);
	private static final int BATCH_SIZE = 500;
	private static final int MAX_SAVE_RETRIES = 3;
	private static final int PERSON_NAME_MAX_LENGTH = 160;
	private static final int PERSON_DEPARTMENT_MAX_LENGTH = 160;
	private static final int ROOM_NAME_MAX_LENGTH = 120;
	private static final String DEFAULT_PERSON_DEPARTMENT = "General";
	private static final Set<WeekDay> DEFAULT_PERSON_AVAILABLE_DAYS = EnumSet.of(
		WeekDay.SUN,
		WeekDay.MON,
		WeekDay.TUE,
		WeekDay.WED,
		WeekDay.THU
	);
	private static final int DEFAULT_ROOM_CAPACITY = 30;
	private static final RoomType DEFAULT_ROOM_TYPE = RoomType.MEDIUM;
	private static final int DEFAULT_MIN_INVIGILATORS = 2;

	private final PersonRepository personRepository;
	private final RoomRepository roomRepository;
	private final ValidationService validationService;
	private final ExcelParserUtil excelParserUtil;
	private final DuplicateResolverUtil duplicateResolverUtil;
	private final NormalizedNameMaintenanceService normalizedNameMaintenanceService;
	private final EntityManager entityManager;
	private final TransactionTemplate transactionTemplate;

	public BulkUploadService(
		PersonRepository personRepository,
		RoomRepository roomRepository,
		ValidationService validationService,
		ExcelParserUtil excelParserUtil,
		DuplicateResolverUtil duplicateResolverUtil,
		NormalizedNameMaintenanceService normalizedNameMaintenanceService,
		EntityManager entityManager,
		PlatformTransactionManager transactionManager
	) {
		this.personRepository = personRepository;
		this.roomRepository = roomRepository;
		this.validationService = validationService;
		this.excelParserUtil = excelParserUtil;
		this.duplicateResolverUtil = duplicateResolverUtil;
		this.normalizedNameMaintenanceService = normalizedNameMaintenanceService;
		this.entityManager = entityManager;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public BulkUploadResultDTO uploadPersons(MultipartFile file, BulkDuplicateStrategy strategy) {
		normalizedNameMaintenanceService.synchronizePeople();

		ExcelParserUtil.ParseResult<PersonImportSource> parseResult = excelParserUtil.parse(
			file,
			values -> new PersonImportSource(
				values.getString(0),
				values.getString(1),
				values.getString(2),
				values.getString(3)
			)
		);

		LOGGER.info("Bulk persons upload started: fileSizeBytes={}, rows={}", file.getSize(), parseResult.totalRows());
		if (parseResult.rows().isEmpty()) {
			return emptyUploadResult("The uploaded persons workbook contains headers only or no data rows.");
		}

		ValidationService.ValidationResult<PersonImportCommand> validation = validationService.validateRows(
			parseResult.rows(),
			this::validatePersonRow
		);

		BulkUploadResultDTO result = persistWithRetry(
			validation.validRows(),
			validation.errors(),
			strategy,
			PERSON_NAME_MAX_LENGTH,
			() -> new LinkedHashSet<>(personRepository.findAllNormalizedNames()),
			PersonImportCommand::name,
			this::toPersonEntity,
			personRepository,
			"persons"
		);

		logValidationSummary("persons", result);
		return result;
	}

	public BulkUploadResultDTO uploadRooms(MultipartFile file, BulkDuplicateStrategy strategy) {
		normalizedNameMaintenanceService.synchronizeRooms();

		ExcelParserUtil.ParseResult<RoomImportSource> parseResult = excelParserUtil.parse(
			file,
			values -> new RoomImportSource(
				values.getString(0),
				values.getString(1),
				values.getString(2),
				values.getString(3)
			)
		);

		LOGGER.info("Bulk rooms upload started: fileSizeBytes={}, rows={}", file.getSize(), parseResult.totalRows());
		if (parseResult.rows().isEmpty()) {
			return emptyUploadResult("The uploaded rooms workbook contains headers only or no data rows.");
		}

		ValidationService.ValidationResult<RoomImportCommand> validation = validationService.validateRows(
			parseResult.rows(),
			this::validateRoomRow
		);

		BulkUploadResultDTO result = persistWithRetry(
			validation.validRows(),
			validation.errors(),
			strategy,
			ROOM_NAME_MAX_LENGTH,
			() -> new LinkedHashSet<>(roomRepository.findAllNormalizedNames()),
			RoomImportCommand::name,
			this::toRoomEntity,
			roomRepository,
			"rooms"
		);

		logValidationSummary("rooms", result);
		return result;
	}

	public byte[] createPersonsTemplate() {
		return createWorkbook(
			"Persons",
			List.of("Name", "Type", "Department (optional)", "Available Days (optional, comma-separated)"),
			List.of(
				List.of("Ahmed Ali", "CHIEF_INVIGILATOR", "Mathematics", "Sun, Mon, Tue, Wed, Thu"),
				List.of("Sara Hassan", "INVIGILATOR", "Physics", "Sun, Tue, Thu")
			),
			sheet -> addDropdownValidation(sheet, 1, 5000, 1, 1, enumNames(PersonRole.values()))
		);
	}

	public byte[] createRoomsTemplate() {
		return createWorkbook(
			"Rooms",
			List.of("Name", "Capacity (optional)", "Type (optional)", "Min Invigilators (optional)"),
			List.of(
				List.of("Hall A", "80", "LARGE", "4"),
				List.of("Lab 2", "30", "MEDIUM", "2")
			),
			sheet -> addDropdownValidation(sheet, 1, 5000, 2, 2, enumNames(RoomType.values()))
		);
	}

	private ValidationService.RowValidationResult<PersonImportCommand> validatePersonRow(PersonImportSource source) {
		List<String> errors = new ArrayList<>();
		String name = NameNormalizationUtil.normalizeWhitespace(source.name());
		if (name == null || name.isBlank()) {
			errors.add("Name is empty.");
		} else if (name.length() > PERSON_NAME_MAX_LENGTH) {
			errors.add("Name must be at most 160 characters.");
		}

		Optional<PersonRole> role = parseEnum(source.type(), PersonRole::valueOf);
		if (role.isEmpty()) {
			errors.add("Invalid type. Expected CHIEF_INVIGILATOR or INVIGILATOR.");
		}

		String department = defaultIfBlank(NameNormalizationUtil.normalizeWhitespace(source.department()), DEFAULT_PERSON_DEPARTMENT);
		if (department.length() > PERSON_DEPARTMENT_MAX_LENGTH) {
			errors.add("Department must be at most 160 characters.");
		}

		Set<WeekDay> availableDays = parseWeekDays(source.availableDays(), errors);
		if (!errors.isEmpty()) {
			return ValidationService.RowValidationResult.failure(errors);
		}

		return ValidationService.RowValidationResult.success(new PersonImportCommand(name, role.orElseThrow(), department, availableDays));
	}

	private ValidationService.RowValidationResult<RoomImportCommand> validateRoomRow(RoomImportSource source) {
		List<String> errors = new ArrayList<>();
		String name = NameNormalizationUtil.normalizeWhitespace(source.name());
		if (name == null || name.isBlank()) {
			errors.add("Name is empty.");
		} else if (name.length() > ROOM_NAME_MAX_LENGTH) {
			errors.add("Name must be at most 120 characters.");
		}

		Integer capacity = parseInteger(source.capacity(), DEFAULT_ROOM_CAPACITY, "Capacity", 1, errors);

		// FIX: store normalizeWhitespace result to avoid calling it twice on the same value
		String normalizedType = NameNormalizationUtil.normalizeWhitespace(source.type());
		Optional<RoomType> roomType = parseEnum(source.type(), RoomType::valueOf);
		if (normalizedType == null || normalizedType.isBlank()) {
			roomType = Optional.of(DEFAULT_ROOM_TYPE);
		} else if (roomType.isEmpty()) {
			errors.add("Invalid room type. Expected SMALL, MEDIUM, or LARGE.");
		}

		Integer minInvigilators = parseInteger(source.minInvigilators(), DEFAULT_MIN_INVIGILATORS, "Minimum invigilators", 0, errors);
		if (!errors.isEmpty()) {
			return ValidationService.RowValidationResult.failure(errors);
		}

		return ValidationService.RowValidationResult.success(
			new RoomImportCommand(name, capacity, roomType.orElse(DEFAULT_ROOM_TYPE), minInvigilators)
		);
	}

	private Set<WeekDay> parseWeekDays(String value, List<String> errors) {
		String normalized = NameNormalizationUtil.normalizeWhitespace(value);
		if (normalized == null || normalized.isBlank()) {
			return new LinkedHashSet<>(DEFAULT_PERSON_AVAILABLE_DAYS);
		}

		Set<WeekDay> days = new LinkedHashSet<>();
		for (String token : normalized.split(",")) {
			String candidate = NameNormalizationUtil.normalizeWhitespace(token);
			if (candidate == null || candidate.isBlank()) {
				continue;
			}

			try {
				days.add(WeekDay.fromValue(candidate));
			} catch (IllegalArgumentException exception) {
				errors.add("Invalid available day: " + candidate + ".");
			}
		}

		if (days.isEmpty()) {
			return new LinkedHashSet<>(DEFAULT_PERSON_AVAILABLE_DAYS);
		}

		return days;
	}

	private Integer parseInteger(String rawValue, int defaultValue, String fieldLabel, int minValue, List<String> errors) {
		String normalized = NameNormalizationUtil.normalizeWhitespace(rawValue);
		if (normalized == null || normalized.isBlank()) {
			return defaultValue;
		}

		try {
			int value = Integer.parseInt(normalized);
			if (value < minValue) {
				errors.add(fieldLabel + " must be at least " + minValue + ".");
			}

			return value;
		} catch (NumberFormatException exception) {
			errors.add(fieldLabel + " must be a whole number.");
			return null;
		}
	}

	private <TRecord, TEntity> BulkUploadResultDTO persistWithRetry(
		List<ExcelParserUtil.ParsedRow<TRecord>> validRows,
		List<BulkUploadErrorDTO> baseErrors,
		BulkDuplicateStrategy strategy,
		int maxNameLength,
		Supplier<Set<String>> existingNamesSupplier,
		Function<TRecord, String> nameExtractor,
		BiFunction<TRecord, String, TEntity> entityFactory,
		JpaRepository<TEntity, ?> repository,
		String uploadLabel
	) {
		DataIntegrityViolationException lastFailure = null;

		for (int attempt = 1; attempt <= MAX_SAVE_RETRIES; attempt++) {
			SavePlan<TEntity> savePlan = buildSavePlan(
				validRows,
				baseErrors,
				strategy,
				maxNameLength,
				existingNamesSupplier.get(),
				nameExtractor,
				entityFactory
			);

			try {
				transactionTemplate.executeWithoutResult(status -> saveInChunks(savePlan.entities(), repository));
				LOGGER.info(
					"Bulk {} upload completed: totalRows={}, successCount={}, failedCount={}, duplicatesResolved={}, skippedDuplicates={}",
					uploadLabel,
					validRows.size() + baseErrors.size(),
					savePlan.entities().size(),
					BulkUploadResultDTO.from(savePlan.entities().size(), savePlan.errors()).failedCount(),
					savePlan.duplicatesResolved(),
					savePlan.skippedDuplicates()
				);
				return BulkUploadResultDTO.from(savePlan.entities().size(), savePlan.errors());
			} catch (DataIntegrityViolationException exception) {
				lastFailure = exception;
				LOGGER.warn(
					"Bulk {} upload detected a concurrent name conflict on attempt {} of {}. Retrying with refreshed normalized names.",
					uploadLabel,
					attempt,
					MAX_SAVE_RETRIES
				);
			}
		}

		LOGGER.error("Bulk {} upload failed after {} attempts.", uploadLabel, MAX_SAVE_RETRIES, lastFailure);
		throw new ValidationException("The upload could not be completed because names kept changing concurrently. Please retry.");
	}

	private <TRecord, TEntity> SavePlan<TEntity> buildSavePlan(
		List<ExcelParserUtil.ParsedRow<TRecord>> validRows,
		List<BulkUploadErrorDTO> baseErrors,
		BulkDuplicateStrategy strategy,
		int maxNameLength,
		Set<String> reservedNormalizedNames,
		Function<TRecord, String> nameExtractor,
		BiFunction<TRecord, String, TEntity> entityFactory
	) {
		List<BulkUploadErrorDTO> errors = new ArrayList<>(baseErrors);
		List<TEntity> entities = new ArrayList<>();
		int duplicatesResolved = 0;
		int skippedDuplicates = 0;

		for (ExcelParserUtil.ParsedRow<TRecord> validRow : validRows) {
			String originalName = nameExtractor.apply(validRow.value());
			String normalizedName = NameNormalizationUtil.normalizeForComparison(originalName);

			if (reservedNormalizedNames.contains(normalizedName) && strategy == BulkDuplicateStrategy.SKIP_DUPLICATES) {
				errors.add(new BulkUploadErrorDTO(validRow.rowNumber(), "Duplicate name skipped: " + originalName));
				skippedDuplicates++;
				continue;
			}

			DuplicateResolverUtil.ResolvedName resolvedName = duplicateResolverUtil.resolveUniqueName(
				originalName,
				reservedNormalizedNames,
				maxNameLength
			);

			if (resolvedName.duplicateResolved()) {
				duplicatesResolved++;
			}

			entities.add(entityFactory.apply(validRow.value(), resolvedName.value()));
		}

		return new SavePlan<>(entities, errors, duplicatesResolved, skippedDuplicates);
	}

	private <TEntity> void saveInChunks(List<TEntity> entities, JpaRepository<TEntity, ?> repository) {
		for (int startIndex = 0; startIndex < entities.size(); startIndex += BATCH_SIZE) {
			int endIndex = Math.min(startIndex + BATCH_SIZE, entities.size());
			repository.saveAll(entities.subList(startIndex, endIndex));
			repository.flush();
			entityManager.clear();
		}
	}

	private Person toPersonEntity(PersonImportCommand command, String resolvedName) {
		Person person = new Person();
		person.setName(resolvedName);
		person.setDepartment(command.department());
		person.setRole(command.role());
		person.setAvailableDays(new LinkedHashSet<>(command.availableDays()));
		person.setTotalAssignments(0);
		person.setActive(true);
		return person;
	}

	private Room toRoomEntity(RoomImportCommand command, String resolvedName) {
		Room room = new Room();
		room.setName(resolvedName);
		room.setCapacity(command.capacity());
		room.setType(command.type());
		room.setMinInvigilators(command.minInvigilators());
		room.setActive(true);
		return room;
	}

	private BulkUploadResultDTO emptyUploadResult(String message) {
		return BulkUploadResultDTO.from(0, List.of(new BulkUploadErrorDTO(0, message)));
	}

	private void logValidationSummary(String uploadLabel, BulkUploadResultDTO result) {
		if (result.failedCount() == 0) {
			LOGGER.info("Bulk {} upload finished without row-level errors.", uploadLabel);
			return;
		}

		LOGGER.warn("Bulk {} upload finished with {} failed rows and {} successful rows.", uploadLabel, result.failedCount(), result.successCount());
	}

	private <T extends Enum<T>> Optional<T> parseEnum(String value, Function<String, T> parser) {
		String normalizedToken = NameNormalizationUtil.normalizeEnumToken(value);
		if (normalizedToken.isBlank()) {
			return Optional.empty();
		}

		try {
			return Optional.of(parser.apply(normalizedToken));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private String defaultIfBlank(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value;
	}

	private byte[] createWorkbook(
		String sheetName,
		List<String> headers,
		List<List<String>> exampleRows,
		SheetCustomizer sheetCustomizer
	) {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet(sheetName);
			CellStyle headerStyle = createHeaderStyle(workbook);

			Row headerRow = sheet.createRow(0);
			for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
				Cell cell = headerRow.createCell(columnIndex);
				cell.setCellValue(headers.get(columnIndex));
				cell.setCellStyle(headerStyle);
			}

			for (int rowIndex = 0; rowIndex < exampleRows.size(); rowIndex++) {
				Row row = sheet.createRow(rowIndex + 1);
				List<String> values = exampleRows.get(rowIndex);
				for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
					row.createCell(columnIndex).setCellValue(values.get(columnIndex));
				}
			}

			sheet.createFreezePane(0, 1);
			sheetCustomizer.customize(sheet);
			for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
				sheet.autoSizeColumn(columnIndex);
			}

			workbook.write(outputStream);
			return outputStream.toByteArray();
		} catch (IOException exception) {
			throw new ValidationException("The Excel template could not be generated.");
		}
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

	private void addDropdownValidation(Sheet sheet, int firstRow, int lastRow, int firstColumn, int lastColumn, String[] values) {
		DataValidationHelper helper = sheet.getDataValidationHelper();
		DataValidationConstraint constraint = helper.createExplicitListConstraint(values);
		CellRangeAddressList addressList = new CellRangeAddressList(firstRow, lastRow, firstColumn, lastColumn);
		DataValidation validation = helper.createValidation(constraint, addressList);
		validation.setSuppressDropDownArrow(false);
		validation.createErrorBox("Invalid value", "Please select a value from the dropdown list.");
		sheet.addValidationData(validation);
	}

	private String[] enumNames(Enum<?>[] values) {
		String[] names = new String[values.length];
		for (int index = 0; index < values.length; index++) {
			names[index] = values[index].name();
		}
		return names;
	}

	private interface SheetCustomizer {
		void customize(Sheet sheet);
	}

	private record PersonImportSource(String name, String type, String department, String availableDays) {
	}

	private record PersonImportCommand(String name, PersonRole role, String department, Set<WeekDay> availableDays) {
	}

	private record RoomImportSource(String name, String capacity, String type, String minInvigilators) {
	}

	private record RoomImportCommand(String name, int capacity, RoomType type, int minInvigilators) {
	}

	private record SavePlan<TEntity>(
		List<TEntity> entities,
		List<BulkUploadErrorDTO> errors,
		int duplicatesResolved,
		int skippedDuplicates
	) {
	}
}