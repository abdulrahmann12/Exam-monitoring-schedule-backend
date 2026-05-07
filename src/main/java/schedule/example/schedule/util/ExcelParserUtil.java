package schedule.example.schedule.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import schedule.example.schedule.exception.ValidationException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ExcelParserUtil {

	public <T> ParseResult<T> parse(MultipartFile file, ExcelRowMapper<T> rowMapper) {
		validateWorkbook(file);

		try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
			if (workbook.getNumberOfSheets() == 0) {
				return new ParseResult<>(List.of(), 0);
			}

			Sheet sheet = workbook.getSheetAt(0);
			DataFormatter formatter = new DataFormatter(Locale.ROOT, true);
			FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
			List<ParsedRow<T>> rows = new ArrayList<>();

			for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
				Row row = sheet.getRow(rowIndex);
				if (row == null || isEmptyRow(row, formatter, evaluator)) {
					continue;
				}

				rows.add(new ParsedRow<>(rowIndex + 1, rowMapper.map(new RowValues(row, formatter, evaluator))));
			}

			return new ParseResult<>(rows, rows.size());
		} catch (IOException exception) {
			throw new ValidationException("The uploaded Excel file could not be read.");
		}
	}

	private void validateWorkbook(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ValidationException("The uploaded Excel file is empty.");
		}

		String filename = file.getOriginalFilename();
		if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
			throw new ValidationException("Only .xlsx files are supported.");
		}
	}

	private boolean isEmptyRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
		int lastCellIndex = Math.max(row.getLastCellNum(), 0);
		for (int cellIndex = 0; cellIndex < lastCellIndex; cellIndex++) {
			if (!formatCell(row.getCell(cellIndex), formatter, evaluator).isBlank()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Static utility used by both {@link #isEmptyRow} and {@link RowValues#getString}.
	 * Extracted to avoid duplication and to allow {@link RowValues} to be a static class.
	 */
	static String formatCell(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
		if (cell == null) {
			return "";
		}
		return formatter.formatCellValue(cell, evaluator).trim();
	}

	@FunctionalInterface
	public interface ExcelRowMapper<T> {
		T map(RowValues values);
	}

	public record ParseResult<T>(List<ParsedRow<T>> rows, int totalRows) {
	}

	public record ParsedRow<T>(int rowNumber, T value) {
	}

	/**
	 * Provides typed cell access for a single Excel row.
	 *
	 * <p><strong>Fix:</strong> Changed from a non-static inner class to a {@code static}
	 * nested class. The previous non-static version held an implicit reference to the
	 * enclosing {@link ExcelParserUtil} singleton bean, preventing GC of intermediate
	 * objects during large file processing and causing memory pressure.
	 */
	public static final class RowValues {

		private final Row row;
		private final DataFormatter formatter;
		private final FormulaEvaluator evaluator;

		RowValues(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
			this.row = row;
			this.formatter = formatter;
			this.evaluator = evaluator;
		}

		public String getString(int cellIndex) {
			return formatCell(row.getCell(cellIndex), formatter, evaluator);
		}
	}
}