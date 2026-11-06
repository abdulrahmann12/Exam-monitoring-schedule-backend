package com.uniguard.schedule.dto.common;

public record FieldValidationError(
	String field,
	String message
) {
}