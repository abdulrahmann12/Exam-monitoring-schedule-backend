package schedule.example.schedule.dto.common;

public record FieldValidationError(
	String field,
	String message
) {
}