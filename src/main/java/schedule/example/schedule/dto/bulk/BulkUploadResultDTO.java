package schedule.example.schedule.dto.bulk;

import java.util.Comparator;
import java.util.List;

public record BulkUploadResultDTO(
	int successCount,
	int failedCount,
	List<BulkUploadErrorDTO> errors
) {

	public static BulkUploadResultDTO from(int successCount, List<BulkUploadErrorDTO> errors) {
		List<BulkUploadErrorDTO> sortedErrors = errors.stream()
			.sorted(Comparator.comparingInt(BulkUploadErrorDTO::row).thenComparing(BulkUploadErrorDTO::message))
			.toList();

		int failedCount = (int) sortedErrors.stream()
			.map(BulkUploadErrorDTO::row)
			.distinct()
			.count();

		return new BulkUploadResultDTO(successCount, failedCount, sortedErrors);
	}
}