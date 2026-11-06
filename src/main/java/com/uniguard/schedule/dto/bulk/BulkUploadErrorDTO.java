package com.uniguard.schedule.dto.bulk;

public record BulkUploadErrorDTO(
	int row,
	String message
) {
}