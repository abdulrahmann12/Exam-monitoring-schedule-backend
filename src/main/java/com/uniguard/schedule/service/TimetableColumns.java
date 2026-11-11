package com.uniguard.schedule.service;

import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.exception.ValidationException;
import com.uniguard.schedule.util.NameNormalizationUtil;
import com.uniguard.schedule.util.TabularFile;

import java.text.MessageFormat;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the timetable columns by their header names, so files from our own template and exports
 * from student-information systems are read alike:
 *
 * <ul>
 *   <li>Uni-Guard template: Date, Time Slot, Room, Subject Name, Subject Code, Students.</li>
 *   <li>Banner: Exam Date, Exam Start Time, Exam End Time, Building + Room, Subject + Course Number, Title, Enrollment.</li>
 *   <li>PeopleSoft: Exam Date, Exam Start Time, Exam End Time, Facility ID, Subject + Catalog Nbr, Descr, Enrl Tot.</li>
 *   <li>Odoo (OpenEduCat): Exam Date / Start Time / End Time, Classroom, Subject, Students.</li>
 * </ul>
 *
 * Headers are compared ignoring case, spaces and punctuation ("Exam_Start Time" = "examstarttime").
 * Dates and times are read flexibly; dates like 03/04/2027 are only accepted when the whole file
 * shows (or the user tells) whether the day or the month comes first.
 */
final class TimetableColumns {

	enum Field { DATE, DAY, SLOT, START, END, ROOM, BUILDING, NAME, CODE, SUBJECT_AREA, CATALOG, STUDENTS, ROOM_TYPE, GENDER }

	private static final Map<Field, Set<String>> ALIASES = new LinkedHashMap<>();

	static {
		ALIASES.put(Field.DATE, Set.of("date", "dateyyyymmdd", "examdate", "examinationdate", "meetingdate", "testdate", "examday"));
		ALIASES.put(Field.DAY, Set.of("day", "dayname", "weekday", "dayofweek", "dayoftheweek"));
		ALIASES.put(Field.SLOT, Set.of("timeslot", "slot", "examslot", "time", "examtime", "sessiontime"));
		ALIASES.put(Field.START, Set.of("starttime", "examstarttime", "begintime", "beginningtime", "fromtime", "timefrom", "from", "start", "begin", "meetingstarttime"));
		ALIASES.put(Field.END, Set.of("endtime", "examendtime", "finishtime", "totime", "timeto", "to", "end", "finish", "meetingendtime"));
		ALIASES.put(Field.ROOM, Set.of("room", "roomname", "roomnumber", "roomcode", "classroom", "facility", "facilityid", "location", "venue", "hall", "examroom", "examhall"));
		ALIASES.put(Field.BUILDING, Set.of("building", "bldg", "buildingcode", "bldgcode"));
		ALIASES.put(Field.NAME, Set.of("subjectname", "coursetitle", "title", "descr", "description", "coursename", "examname", "coursedescription", "moduletitle", "modulename"));
		ALIASES.put(Field.CODE, Set.of("subjectcode", "coursecode", "courseid", "course", "code", "module", "modulecode", "examcode"));
		ALIASES.put(Field.SUBJECT_AREA, Set.of("subject", "subj", "subjectarea", "subjcode"));
		ALIASES.put(Field.CATALOG, Set.of("coursenumber", "crsenumb", "catalognbr", "catalognumber", "catalog", "crse", "courseno", "coursenum"));
		ALIASES.put(Field.GENDER, Set.of("gender", "studentgender", "studentsgender", "hallgender", "sex", "malefemale"));
		ALIASES.put(Field.ROOM_TYPE, Set.of("roomtype", "roomkind", "roomcategory", "typeofroom", "venuetype", "roomtypeoptional"));
		ALIASES.put(Field.STUDENTS, Set.of("students", "studentsoptional", "studentcount", "enrollment", "enrolment", "enrltot", "enrolled",
			"actualenrollment", "actualenrolment", "headcount", "noofstudents", "numberofstudents", "totalstudents", "candidates", "noofcandidates"));
	}

	private final Map<Field, Integer> columns = new LinkedHashMap<>();
	private final List<String> recognised = new ArrayList<>();

	private TimetableColumns() {
	}

	/** Recognises the columns from the header row; fails with a clear message when the essentials are missing. */
	static TimetableColumns of(List<String> headers) {
		return of(headers, true, true);
	}

	static TimetableColumns of(List<String> headers, boolean roomRequired) {
		return of(headers, roomRequired, true);
	}

	/**
	 * @param roomRequired false for room allocation, where the system picks the rooms and a room
	 *                     column (if any) is ignored
	 */
	/** @param timeRequired false for room allocation: an empty or missing time slot is chosen by the system */
	static TimetableColumns of(List<String> headers, boolean roomRequired, boolean timeRequired) {
		TimetableColumns result = new TimetableColumns();
		for (int index = 0; index < headers.size(); index++) {
			String key = normalize(headers.get(index));
			for (Map.Entry<Field, Set<String>> alias : ALIASES.entrySet()) {
				if (!result.columns.containsKey(alias.getKey()) && alias.getValue().contains(key)) {
					result.columns.put(alias.getKey(), index);
					result.recognised.add(headers.get(index).trim() + " → " + label(alias.getKey()));
					break;
				}
			}
		}
		boolean hasTime = !timeRequired || result.has(Field.SLOT) || (result.has(Field.START) && result.has(Field.END));
		boolean roomMissing = roomRequired && !result.has(Field.ROOM);
		if (!result.has(Field.DATE) || roomMissing || !hasTime) {
			List<String> missing = new ArrayList<>();
			if (!result.has(Field.DATE)) {
				missing.add("date (e.g. \"Date\" or \"Exam Date\")");
			}
			if (!hasTime) {
				missing.add("time (\"Time Slot\", or \"Start Time\" and \"End Time\")");
			}
			if (roomMissing) {
				missing.add("room (e.g. \"Room\", \"Classroom\" or \"Facility ID\")");
			}
			throw new ValidationException("Could not find these columns in the first row: " + String.join(", ", missing)
				+ ". Found: " + String.join(", ", headers.stream().filter(h -> h != null && !h.isBlank()).toList()) + ".");
		}
		return result;
	}

	boolean has(Field field) {
		return columns.containsKey(field);
	}

	TabularFile.CellValue cell(TabularFile.DataRow row, Field field) {
		Integer index = columns.get(field);
		return index == null ? new TabularFile.CellValue("", null, null) : row.get(index);
	}

	String text(TabularFile.DataRow row, Field field) {
		return cell(row, field).text();
	}

	/** Subject code: its own column, or subject area + catalog number (Banner/PeopleSoft: MATH + 101 = MATH101). */
	String subjectCode(TabularFile.DataRow row) {
		if (has(Field.CODE)) {
			return text(row, Field.CODE);
		}
		if (has(Field.SUBJECT_AREA) && has(Field.CATALOG)) {
			String area = text(row, Field.SUBJECT_AREA).trim();
			String number = text(row, Field.CATALOG).trim();
			return (area + number).isBlank() ? "" : area + number;
		}
		return "";
	}

	/** Subject name: its own column; Odoo's "Subject" holds the name when there is no catalog number. */
	String subjectName(TabularFile.DataRow row) {
		if (has(Field.NAME)) {
			return text(row, Field.NAME);
		}
		if (has(Field.SUBJECT_AREA) && !has(Field.CATALOG)) {
			return text(row, Field.SUBJECT_AREA);
		}
		return "";
	}

	List<String> recognised() {
		return List.copyOf(recognised);
	}

	static String normalize(String header) {
		return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	private static String label(Field field) {
		return switch (field) {
			case DATE -> "date";
			case DAY -> "day";
			case SLOT -> "time slot";
			case START -> "start time";
			case END -> "end time";
			case ROOM -> "room";
			case BUILDING -> "building";
			case NAME -> "subject name";
			case CODE -> "subject code";
			case SUBJECT_AREA -> "subject";
			case CATALOG -> "course number";
			case STUDENTS -> "students";
			case ROOM_TYPE -> "room type";
			case GENDER -> "gender";
		};
	}

	// ─────────────────────────── Dates ───────────────────────────

	enum DateOrder { DAY_FIRST, MONTH_FIRST }

	private static final Pattern NUMERIC_DATE = Pattern.compile("^(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2}|\\d{4})$");
	private static final List<DateTimeFormatter> TEXT_DATES = List.of(
		DateTimeFormatter.ISO_LOCAL_DATE,
		DateTimeFormatter.ofPattern("uuuu/M/d"),
		new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-uuuu").toFormatter(Locale.ENGLISH),
		new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-uu").toFormatter(Locale.ENGLISH),
		new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uuuu").toFormatter(Locale.ENGLISH),
		new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMMM uuuu").toFormatter(Locale.ENGLISH),
		new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM d, uuuu").toFormatter(Locale.ENGLISH));

	/**
	 * Decides the order of dates like 03/04/2027 from the whole file: a first number above 12 means
	 * day first, a second number above 12 means month first. Null when the file cannot tell.
	 */
	static DateOrder detectOrder(List<String> texts) {
		boolean dayFirst = false;
		boolean monthFirst = false;
		for (String text : texts) {
			Matcher matcher = NUMERIC_DATE.matcher(text == null ? "" : text.trim());
			if (matcher.matches()) {
				int first = Integer.parseInt(matcher.group(1));
				int second = Integer.parseInt(matcher.group(2));
				dayFirst |= first > 12;
				monthFirst |= second > 12;
			}
		}
		if (dayFirst && monthFirst) {
			throw new ValidationException("The dates mix day-first and month-first (e.g. 25/03 and 03/25). Use YYYY-MM-DD.");
		}
		return dayFirst ? DateOrder.DAY_FIRST : monthFirst ? DateOrder.MONTH_FIRST : null;
	}

	/** @return the date, or null with an error message added */
	static LocalDate parseDate(TabularFile.CellValue cell, DateOrder order, List<String> errors) {
		if (cell.date() != null) {
			return cell.date();
		}
		String text = cell.text().trim();
		if (text.isEmpty()) {
			errors.add("Date is required.");
			return null;
		}
		for (DateTimeFormatter format : TEXT_DATES) {
			try {
				return LocalDate.parse(text, format);
			} catch (DateTimeParseException ignored) {
				// try the next format
			}
		}
		Matcher matcher = NUMERIC_DATE.matcher(text);
		if (matcher.matches()) {
			int first = Integer.parseInt(matcher.group(1));
			int second = Integer.parseInt(matcher.group(2));
			int year = Integer.parseInt(matcher.group(3));
			year = year < 100 ? 2000 + year : year;
			if (first == second) {
				order = order == null ? DateOrder.DAY_FIRST : order; // 05/05 is the same either way
			}
			if (order == null) {
				errors.add("\"" + text + "\" can mean two dates (day/month or month/day). Use YYYY-MM-DD, or choose the date order when uploading.");
				return null;
			}
			try {
				return order == DateOrder.DAY_FIRST ? LocalDate.of(year, second, first) : LocalDate.of(year, first, second);
			} catch (DateTimeException ex) {
				errors.add("\"" + text + "\" is not a valid date.");
				return null;
			}
		}
		errors.add("\"" + text + "\" is not a date (use YYYY-MM-DD).");
		return null;
	}

	// ─────────────────────────── Times ───────────────────────────

	private static final List<DateTimeFormatter> TIMES = List.of(
		DateTimeFormatter.ofPattern("H:mm"),
		DateTimeFormatter.ofPattern("H:mm:ss"),
		DateTimeFormatter.ofPattern("H.mm"),
		new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h:mm a").toFormatter(Locale.ENGLISH),
		new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h:mma").toFormatter(Locale.ENGLISH),
		new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h:mm:ss a").toFormatter(Locale.ENGLISH),
		new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h a").toFormatter(Locale.ENGLISH),
		new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("ha").toFormatter(Locale.ENGLISH));

	static LocalTime parseTime(TabularFile.CellValue cell) {
		if (cell.time() != null) {
			return cell.time();
		}
		return parseTime(cell.text());
	}

	static LocalTime parseTime(String raw) {
		String text = raw == null ? "" : raw.trim().replace(' ', ' ');
		if (text.matches("\\d{3,4}")) { // 0900 / 900
			int value = Integer.parseInt(text);
			try {
				return LocalTime.of(value / 100, value % 100);
			} catch (DateTimeException ex) {
				return null;
			}
		}
		for (DateTimeFormatter format : TIMES) {
			try {
				return LocalTime.parse(text, format);
			} catch (DateTimeParseException ignored) {
				// try the next format
			}
		}
		return null;
	}

	/** "09:00 - 12:00", "9:00 AM – 12:00 PM", "09:00 to 12:00" → start and end; null when not a range. */
	static LocalTime[] parseRange(String raw) {
		if (raw == null) {
			return null;
		}
		String[] parts = raw.trim().split("\\s*(?:-|–|—|\\bto\\b)\\s*", 2);
		if (parts.length != 2) {
			return null;
		}
		LocalTime start = parseTime(parts[0]);
		LocalTime end = parseTime(parts[1]);
		return start == null || end == null ? null : new LocalTime[] {start, end};
	}

	// ─────────────────────────── Shared row helpers ───────────────────────────

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	/** "DAY_FIRST" / "MONTH_FIRST"; empty or "AUTO" = null (decide from the file). */
	static DateOrder parseDateOrder(String value) {
		if (value == null || value.isBlank() || "AUTO".equalsIgnoreCase(value)) {
			return null;
		}
		try {
			return DateOrder.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			throw new ValidationException("dateOrder must be DAY_FIRST, MONTH_FIRST or empty.");
		}
	}

	/** True when the row gives no time at all (no time column, or its time cells are empty). */
	boolean timeBlank(TabularFile.DataRow row) {
		if (has(Field.START) && has(Field.END)) {
			return cell(row, Field.START).isBlank() && cell(row, Field.END).isBlank();
		}
		if (has(Field.SLOT)) {
			return cell(row, Field.SLOT).isBlank();
		}
		return true;
	}

	/** Start and end: separate start/end columns, or one "09:00 - 12:00" column. Null with errors added when wrong. */
	LocalTime[] times(TabularFile.DataRow row, List<String> errors) {
		LocalTime start = null;
		LocalTime end = null;
		if (has(TimetableColumns.Field.START) && has(TimetableColumns.Field.END)) {
			TabularFile.CellValue startCell = cell(row, TimetableColumns.Field.START);
			TabularFile.CellValue endCell = cell(row, TimetableColumns.Field.END);
			start = parseTime(startCell);
			end = parseTime(endCell);
			if (start == null) {
				errors.add(startCell.text().isBlank() ? "Start time is required." : "Start time \"" + startCell.text() + "\" is not a time (use e.g. 09:00).");
			}
			if (end == null) {
				errors.add(endCell.text().isBlank() ? "End time is required." : "End time \"" + endCell.text() + "\" is not a time (use e.g. 12:00).");
			}
		} else {
			String text = NameNormalizationUtil.normalizeWhitespace(text(row, TimetableColumns.Field.SLOT));
			if (text == null || text.isBlank()) {
				errors.add(Messages.SCHEDULE_BULK_ROW_SLOT_REQUIRED);
				return null;
			}
			LocalTime[] range = parseRange(text);
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
			errors.add("The exam must end after it starts (" + start.format(HH_MM) + " – " + end.format(HH_MM) + ").");
			return null;
		}
		return new LocalTime[] {start, end};
	}
}
