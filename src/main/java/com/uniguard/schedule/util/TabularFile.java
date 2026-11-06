package com.uniguard.schedule.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import com.uniguard.schedule.exception.ValidationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a table from an Excel (.xlsx) or CSV file: the first non-empty row is the header, the rest
 * are data rows. Excel date and time cells keep their real value (not only the displayed text),
 * so exports from student-information systems (Banner, PeopleSoft, Odoo) are read correctly
 * whatever display format they use.
 */
public final class TabularFile {

	/** One cell: its text, plus the date/time when Excel stored a real date or time. */
	public record CellValue(String text, LocalDate date, LocalTime time) {
		static final CellValue EMPTY = new CellValue("", null, null);

		public boolean isBlank() {
			return text.isBlank() && date == null && time == null;
		}
	}

	/** @param rowNumber as shown in Excel (1 = first row of the sheet) */
	public record DataRow(int rowNumber, List<CellValue> cells) {
		public CellValue get(int index) {
			return index >= 0 && index < cells.size() ? cells.get(index) : CellValue.EMPTY;
		}
	}

	public record Table(List<String> headers, List<DataRow> rows) {
	}

	private TabularFile() {
	}

	public static Table read(MultipartFile file, int maxRows) {
		if (file == null || file.isEmpty()) {
			throw new ValidationException("The uploaded file is empty.");
		}
		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
		try (InputStream input = file.getInputStream()) {
			if (name.endsWith(".xlsx")) {
				return readExcel(input, maxRows);
			}
			if (name.endsWith(".csv") || name.endsWith(".txt")) {
				return readCsv(input.readAllBytes(), maxRows);
			}
		} catch (IOException ex) {
			throw new ValidationException("The uploaded file could not be read.");
		}
		throw new ValidationException("Upload an Excel (.xlsx) or CSV (.csv) file.");
	}

	// ─────────────────────────── Excel ───────────────────────────

	private static Table readExcel(InputStream input, int maxRows) throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook(input)) {
			if (workbook.getNumberOfSheets() == 0) {
				return new Table(List.of(), List.of());
			}
			Sheet sheet = workbook.getSheetAt(0);
			DataFormatter formatter = new DataFormatter(Locale.ROOT, true);
			FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
			List<String> headers = null;
			List<DataRow> rows = new ArrayList<>();
			for (int index = 0; index <= sheet.getLastRowNum(); index++) {
				Row row = sheet.getRow(index);
				if (row == null) {
					continue;
				}
				List<CellValue> cells = new ArrayList<>();
				int last = Math.max(row.getLastCellNum(), 0);
				for (int column = 0; column < last; column++) {
					cells.add(excelCell(row.getCell(column), formatter, evaluator));
				}
				if (cells.stream().allMatch(CellValue::isBlank)) {
					continue;
				}
				if (headers == null) {
					headers = cells.stream().map(CellValue::text).toList();
					continue;
				}
				rows.add(new DataRow(index + 1, cells));
				requireWithinLimit(rows.size(), maxRows);
			}
			return new Table(headers == null ? List.of() : headers, rows);
		}
	}

	private static CellValue excelCell(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
		if (cell == null) {
			return CellValue.EMPTY;
		}
		String text = formatter.formatCellValue(cell, evaluator).trim();
		CellType type = cell.getCellType() == CellType.FORMULA ? evaluator.evaluateFormulaCell(cell) : cell.getCellType();
		if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
			LocalDateTime value = cell.getLocalDateTimeCellValue();
			double raw = cell.getNumericCellValue();
			// Excel stores a time-only cell as a fraction of a day (date part 0 = 1899-12-31).
			LocalDate date = raw >= 1 ? value.toLocalDate() : null;
			return new CellValue(text, date, value.toLocalTime());
		}
		return new CellValue(text, null, null);
	}

	// ─────────────────────────── CSV ───────────────────────────

	private static Table readCsv(byte[] bytes, int maxRows) {
		String content = decode(bytes);
		if (content.startsWith("﻿")) {
			content = content.substring(1);
		}
		List<List<String>> records = parseCsv(content, delimiter(content));
		List<String> headers = null;
		List<DataRow> rows = new ArrayList<>();
		for (int index = 0; index < records.size(); index++) {
			List<String> record = records.get(index);
			if (record.stream().allMatch(String::isBlank)) {
				continue;
			}
			if (headers == null) {
				headers = record.stream().map(String::trim).toList();
				continue;
			}
			rows.add(new DataRow(index + 1, record.stream().map(text -> new CellValue(text.trim(), null, null)).toList()));
			requireWithinLimit(rows.size(), maxRows);
		}
		return new Table(headers == null ? List.of() : headers, rows);
	}

	/** UTF-8, or Windows-1252 for files saved by older Excel versions. */
	private static String decode(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException ex) {
			return new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
		}
	}

	/** Comma, semicolon (Excel in many European/Arab locales) or tab — whichever the header line uses most. */
	static char delimiter(String content) {
		int end = content.indexOf('\n');
		String header = end < 0 ? content : content.substring(0, end);
		char best = ',';
		long bestCount = header.chars().filter(c -> c == ',').count();
		for (char candidate : new char[] {';', '\t'}) {
			long count = header.chars().filter(c -> c == candidate).count();
			if (count > bestCount) {
				best = candidate;
				bestCount = count;
			}
		}
		return best;
	}

	/** RFC 4180: quoted fields may contain the delimiter, line breaks and doubled quotes. */
	static List<List<String>> parseCsv(String content, char delimiter) {
		List<List<String>> records = new ArrayList<>();
		List<String> record = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < content.length(); i++) {
			char c = content.charAt(i);
			if (quoted) {
				if (c == '"') {
					if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
						field.append('"');
						i++;
					} else {
						quoted = false;
					}
				} else {
					field.append(c);
				}
			} else if (c == '"' && field.isEmpty()) {
				quoted = true;
			} else if (c == delimiter) {
				record.add(field.toString());
				field.setLength(0);
			} else if (c == '\n' || c == '\r') {
				if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
					i++;
				}
				record.add(field.toString());
				field.setLength(0);
				records.add(record);
				record = new ArrayList<>();
			} else {
				field.append(c);
			}
		}
		if (!field.isEmpty() || !record.isEmpty()) {
			record.add(field.toString());
			records.add(record);
		}
		return records;
	}

	private static void requireWithinLimit(int rows, int maxRows) {
		if (rows > maxRows) {
			throw new ValidationException("The file has more than " + maxRows + " rows. Split it into smaller files.");
		}
	}
}
