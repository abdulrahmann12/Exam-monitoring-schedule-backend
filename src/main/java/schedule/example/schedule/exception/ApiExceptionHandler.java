package schedule.example.schedule.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import schedule.example.schedule.dto.common.ApiErrorResponse;
import schedule.example.schedule.dto.common.FieldValidationError;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(NotFoundException.class)
	public org.springframework.http.ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest request) {
		return build(HttpStatus.NOT_FOUND, safeMessage(ex), request, List.of());
	}

	@ExceptionHandler(ConflictException.class)
	public org.springframework.http.ResponseEntity<ApiErrorResponse> handleConflict(ConflictException ex, HttpServletRequest request) {
		return build(HttpStatus.CONFLICT, safeMessage(ex), request, List.of());
	}

	@ExceptionHandler({ValidationException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
	public org.springframework.http.ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, badRequestMessage(ex), request, List.of());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public org.springframework.http.ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
		MethodArgumentNotValidException ex,
		HttpServletRequest request
	) {
		List<FieldValidationError> validationErrors = ex.getBindingResult().getFieldErrors().stream()
			.map(this::toFieldValidationError)
			.toList();

		return build(HttpStatus.BAD_REQUEST, "Validation failed", request, validationErrors);
	}

	@ExceptionHandler(AuthenticationException.class)
	public org.springframework.http.ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
		return build(HttpStatus.UNAUTHORIZED, safeMessage(ex), request, List.of());
	}

	@ExceptionHandler(Exception.class)
	public org.springframework.http.ResponseEntity<ApiErrorResponse> handleUnhandled(Exception ex, HttpServletRequest request) {
		LOGGER.error("Unhandled API exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
		return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected internal error", request, List.of());
	}

	private FieldValidationError toFieldValidationError(FieldError fieldError) {
		return new FieldValidationError(
			fieldError.getField(),
			Objects.requireNonNullElse(fieldError.getDefaultMessage(), "Invalid value")
		);
	}

	private String safeMessage(Exception ex) {
		return Objects.requireNonNullElse(ex.getMessage(), "Unexpected error");
	}

	private String badRequestMessage(Exception ex) {
		if (ex instanceof MethodArgumentTypeMismatchException mismatchException) {
			String parameterName = Objects.requireNonNullElse(mismatchException.getName(), "unknown");
			return "Invalid value for parameter '%s'".formatted(parameterName);
		}

		return safeMessage(ex);
	}

	private org.springframework.http.ResponseEntity<ApiErrorResponse> build(
		HttpStatus status,
		String message,
		HttpServletRequest request,
		List<FieldValidationError> validationErrors
	) {
		ApiErrorResponse error = new ApiErrorResponse(
			Instant.now(),
			status.value(),
			status.getReasonPhrase(),
			message,
			request.getRequestURI(),
			validationErrors
		);

		return org.springframework.http.ResponseEntity.status(status).body(error);
	}
}