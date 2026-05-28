package schedule.example.schedule.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.orm.jpa.JpaSystemException;

import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.common.ApiErrorResponse;
import schedule.example.schedule.dto.common.FieldValidationError;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Centralized REST exception handler.
 * - Stack traces are logged server-side only; clients receive generic messages.
 * - All handlers delegate to buildErrorResponse() for a consistent response envelope.
 * - AuthenticationException/AccessDeniedException are caught here as a safety net;
 *   normally they are handled by RestAuthenticationEntryPoint/RestAccessDeniedHandler.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** Maps Python service error codes to HTTP statuses. Initialized once at class-load. */
	private static final Map<String, HttpStatus> PYTHON_ERROR_CODE_STATUS_MAP = Map.ofEntries(
		Map.entry("PYTHON_400", HttpStatus.BAD_REQUEST),
		Map.entry("PYTHON_401", HttpStatus.UNAUTHORIZED),
		Map.entry("PYTHON_403", HttpStatus.FORBIDDEN),
		Map.entry("PYTHON_404", HttpStatus.NOT_FOUND),
		Map.entry("PYTHON_409", HttpStatus.CONFLICT),
		Map.entry("PYTHON_422", HttpStatus.UNPROCESSABLE_ENTITY),
		Map.entry("PYTHON_429", HttpStatus.TOO_MANY_REQUESTS),
		Map.entry("PYTHON_500", HttpStatus.INTERNAL_SERVER_ERROR),
		Map.entry("PYTHON_502", HttpStatus.BAD_GATEWAY),
		Map.entry("PYTHON_503", HttpStatus.SERVICE_UNAVAILABLE),
		Map.entry("PYTHON_504", HttpStatus.GATEWAY_TIMEOUT)
	);

	// ── 404 NOT FOUND ─────────────────────────────────────────────────────────

	/** Handles resource-not-found exceptions. */
	@ExceptionHandler({
		NotFoundException.class,
		ExamNotFoundException.class,
		SettingsNotFoundException.class
	})
	public ResponseEntity<ApiErrorResponse> handleNotFound(RuntimeException ex, HttpServletRequest request) {
		return buildErrorResponse(HttpStatus.NOT_FOUND, safeMessage(ex), request);
	}

	// ── 409 CONFLICT ──────────────────────────────────────────────────────────

	/** Handles duplicate-resource and state-collision exceptions. */
	@ExceptionHandler({ConflictException.class, DuplicateResourceException.class})
	public ResponseEntity<ApiErrorResponse> handleConflict(RuntimeException ex, HttpServletRequest request) {
		return buildErrorResponse(HttpStatus.CONFLICT, safeMessage(ex), request);
	}


	// ── 400 BAD REQUEST ───────────────────────────────────────────────────────

	/** Handles simple validation failures without per-field breakdown. */
	@ExceptionHandler({
		ValidationException.class,
		MethodArgumentTypeMismatchException.class,
		ConstraintViolationException.class
	})
	public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
		return buildErrorResponse(HttpStatus.BAD_REQUEST, badRequestMessage(ex), request);
	}

	// ── 400 BAD REQUEST — JSON parse error ────────────────────────────────────

	/** Handles malformed JSON bodies. Returns a user-friendly message without leaking Jackson internals. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleJsonParseError(
		HttpMessageNotReadableException ex,
		HttpServletRequest request
	) {
		log.debug("JSON deserialization failure for {} {}: {}",
			request.getMethod(), request.getRequestURI(), ex.getMessage());
		return buildErrorResponse(HttpStatus.BAD_REQUEST, Messages.HTTP_JSON_PARSE_ERROR, request);
	}

	// ── 400 BAD REQUEST — bean validation ─────────────────────────────────────

	/** Handles @Valid/@Validated DTO failures. Returns a per-field error list for the client. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
		MethodArgumentNotValidException ex,
		HttpServletRequest request
	) {
		List<FieldValidationError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
			.map(fe -> new FieldValidationError(
				fe.getField(),
				Objects.requireNonNullElse(fe.getDefaultMessage(), Messages.VALIDATION_FIELD_INVALID)
			))
			.toList();

		return buildErrorResponse(HttpStatus.BAD_REQUEST, Messages.VALIDATION_FAILED, request, fieldErrors);
	}

	// ── 405 METHOD NOT ALLOWED ────────────────────────────────────────────────

	/** Handles requests using an HTTP method not supported by the endpoint. */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
		HttpRequestMethodNotSupportedException ex,
		HttpServletRequest request
	) {
		log.debug("Method not allowed: {} {} — supported: {}",
			ex.getMethod(), request.getRequestURI(), ex.getSupportedHttpMethods());
		return buildErrorResponse(HttpStatus.METHOD_NOT_ALLOWED, Messages.HTTP_METHOD_NOT_SUPPORTED, request);
	}

	// ── 401 UNAUTHORIZED ──────────────────────────────────────────────────────

	/** Handles authentication and token validation failures. */
	@ExceptionHandler({AuthenticationException.class, InvalidTokenException.class})
	public ResponseEntity<ApiErrorResponse> handleAuthentication(Exception ex, HttpServletRequest request) {
		return buildErrorResponse(HttpStatus.UNAUTHORIZED, safeMessage(ex), request);
	}

	// ── 403 FORBIDDEN ─────────────────────────────────────────────────────────

	/** Handles authorization rejections from Spring Security or domain access control. */
	@ExceptionHandler({AccessDeniedException.class, UnauthorizedException.class})
	public ResponseEntity<ApiErrorResponse> handleForbidden(Exception ex, HttpServletRequest request) {
		return buildErrorResponse(HttpStatus.FORBIDDEN, safeMessage(ex), request);
	}

	// ── 403 FORBIDDEN — demo mode ─────────────────────────────────────────────

	/** Handles operations blocked for the demo account. */
	@ExceptionHandler(DemoOperationNotAllowedException.class)
	public ResponseEntity<ApiErrorResponse> handleDemoOperationNotAllowed(
		DemoOperationNotAllowedException ex,
		HttpServletRequest request
	) {
		return buildErrorResponse(HttpStatus.FORBIDDEN, safeMessage(ex), request);
	}

	// ── 422 UNPROCESSABLE ENTITY ──────────────────────────────────────────────

	/** Handles well-formed requests that violate domain business rules. */
	@ExceptionHandler({
		BusinessRuleViolationException.class,
		InvalidExamTimeException.class,
		InvigilatorUnavailableException.class
	})
	public ResponseEntity<ApiErrorResponse> handleBusinessRuleViolation(
		RuntimeException ex,
		HttpServletRequest request
	) {
		return buildErrorResponse(HttpStatus.UNPROCESSABLE_ENTITY, safeMessage(ex), request);
	}

	// ── 429 TOO MANY REQUESTS ─────────────────────────────────────────────────

	/** Handles service-layer rate-limit violations. */
	@ExceptionHandler(RateLimitExceededException.class)
	public ResponseEntity<ApiErrorResponse> handleRateLimitExceeded(
		RateLimitExceededException ex,
		HttpServletRequest request
	) {
		return buildErrorResponse(HttpStatus.TOO_MANY_REQUESTS, safeMessage(ex), request);
	}

	// ── DYNAMIC STATUS — Python scheduling service ────────────────────────────

	/** Translates Python service error codes to HTTP statuses and logs at WARN. */
	@ExceptionHandler(PythonServiceException.class)
	public ResponseEntity<ApiErrorResponse> handlePythonServiceException(
		PythonServiceException ex,
		HttpServletRequest request
	) {
		HttpStatus status = resolveHttpStatusForPythonError(ex.getErrorCode());
		log.warn("Python service error [errorCode={}, resolvedStatus={}] for {} {}: {}",
			ex.getErrorCode(), status.value(), request.getMethod(), request.getRequestURI(), ex.getMessage());
		return buildErrorResponse(status, safeMessage(ex), request);
	}

	// ── 500 INTERNAL SERVER ERROR ─────────────────────────────────────────────

	/** Catch-all handler. Logs full trace server-side; returns a generic message to the client. */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
		return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, Messages.GENERAL_UNEXPECTED_ERROR, request);
	}

	// ── 503 SERVICE UNAVAILABLE — database connectivity ───────────────────────

	/**
	 * Handles database connectivity failures (server down, pool exhaustion, JPA system errors).
	 * All map to 503 because the request is valid but the database is unreachable.
	 */
	@ExceptionHandler({
		DataAccessResourceFailureException.class,
		TransientDataAccessException.class,
		JpaSystemException.class
	})
	public ResponseEntity<ApiErrorResponse> handleDatabaseConnectivityFailure(
			Exception ex,
			HttpServletRequest request
	) {
		log.error("Database connectivity failure for {} {}",
				request.getMethod(),
				request.getRequestURI(),
				ex
		);
		return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, Messages.DATABASE_UNAVAILABLE, request);
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	/** Builds an error response without field errors. */
	private ResponseEntity<ApiErrorResponse> buildErrorResponse(
		HttpStatus status,
		String message,
		HttpServletRequest request
	) {
		return buildErrorResponse(status, message, request, List.of());
	}

	/** Core response builder used by all handlers. */
	private ResponseEntity<ApiErrorResponse> buildErrorResponse(
		HttpStatus status,
		String message,
		HttpServletRequest request,
		List<FieldValidationError> validationErrors
	) {
		ApiErrorResponse body = new ApiErrorResponse(
			Instant.now(),
			status.value(),
			status.getReasonPhrase(),
			message,
			request.getRequestURI(),
			validationErrors
		);
		return ResponseEntity.status(status).body(body);
	}

	/** Returns the exception message, or a fallback if it is null. */
	private String safeMessage(Exception ex) {
		return Objects.requireNonNullElse(ex.getMessage(), Messages.GENERAL_UNEXPECTED_ERROR);
	}

	/**
	 * Resolves a user-facing message for 400 Bad Request cases:
	 * - Type mismatch: names the offending parameter.
	 * - Constraint violation: concatenates all violation messages.
	 * - Other: delegates to safeMessage().
	 */
	private String badRequestMessage(Exception ex) {
		if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
			return Messages.VALIDATION_INVALID_PARAMETER.formatted(mismatch.getName());
		}

		if (ex instanceof ConstraintViolationException cve) {
			String violations = cve.getConstraintViolations().stream()
				.map(v -> v.getPropertyPath() + ": " + v.getMessage())
				.collect(Collectors.joining("; "));
			return violations.isBlank() ? Messages.VALIDATION_FAILED : violations;
		}

		return safeMessage(ex);
	}

	/** Maps a Python service error code to an HTTP status. Defaults to 500 for unknown codes. */
	private HttpStatus resolveHttpStatusForPythonError(String errorCode) {
		if (errorCode == null) {
			return HttpStatus.INTERNAL_SERVER_ERROR;
		}
		return PYTHON_ERROR_CODE_STATUS_MAP.getOrDefault(errorCode, HttpStatus.INTERNAL_SERVER_ERROR);
	}
}

