package schedule.example.schedule.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import schedule.example.schedule.dto.bulk.BulkUploadErrorDTO;
import schedule.example.schedule.util.ExcelParserUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Service
@Validated
@RequiredArgsConstructor
public class ValidationService {

	public <S, T> ValidationResult<T> validateRows(
		List<ExcelParserUtil.ParsedRow<S>> parsedRows,
		Function<S, RowValidationResult<T>> validator
	) {
		List<ExcelParserUtil.ParsedRow<T>> validRows = new ArrayList<>();
		List<BulkUploadErrorDTO> errors = new ArrayList<>();

		for (ExcelParserUtil.ParsedRow<S> parsedRow : parsedRows) {
			RowValidationResult<T> rowResult = validator.apply(parsedRow.value());
			if (rowResult.isValid()) {
				validRows.add(new ExcelParserUtil.ParsedRow<>(parsedRow.rowNumber(), rowResult.value()));
				continue;
			}

			for (String message : rowResult.errors()) {
				errors.add(new BulkUploadErrorDTO(parsedRow.rowNumber(), message));
			}
		}

		return new ValidationResult<>(validRows, errors);
	}

	public record ValidationResult<T>(List<ExcelParserUtil.ParsedRow<T>> validRows, List<BulkUploadErrorDTO> errors) {
	}

	public record RowValidationResult<T>(T value, List<String> errors) {

		public boolean isValid() {
			return errors == null || errors.isEmpty();
		}

		public static <T> RowValidationResult<T> success(T value) {
			return new RowValidationResult<>(value, List.of());
		}

		public static <T> RowValidationResult<T> failure(List<String> errors) {
			return new RowValidationResult<>(null, List.copyOf(errors));
		}
	}
}