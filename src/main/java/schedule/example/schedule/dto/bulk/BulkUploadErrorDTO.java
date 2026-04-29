package schedule.example.schedule.dto.bulk;

public record BulkUploadErrorDTO(
	int row,
	String message
) {
}