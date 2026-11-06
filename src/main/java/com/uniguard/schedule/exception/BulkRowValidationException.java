package com.uniguard.schedule.exception;

import java.util.List;

import com.uniguard.schedule.dto.bulk.BulkUploadErrorDTO;

/**
 * Thrown when a bulk upload (e.g. schedule bulk upload) contains one or more invalid rows.
 *
 * <p>Carries the full list of row-numbered errors so {@code GlobalExceptionHandler} can surface
 * them to the client as {@code validationErrors} (field = "Row {n}", message = the issue) on a
 * single 400 Bad Request response — the whole upload is treated as all-or-nothing.
 */
public class BulkRowValidationException extends RuntimeException {

	private final List<BulkUploadErrorDTO> rowErrors;

	public BulkRowValidationException(String message, List<BulkUploadErrorDTO> rowErrors) {
		super(message);
		this.rowErrors = List.copyOf(rowErrors);
	}

	public List<BulkUploadErrorDTO> getRowErrors() {
		return rowErrors;
	}
}
