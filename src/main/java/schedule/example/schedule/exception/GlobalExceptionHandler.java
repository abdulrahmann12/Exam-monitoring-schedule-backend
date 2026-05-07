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

import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.common.ApiErrorResponse;
import schedule.example.schedule.dto.common.FieldValidationError;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Production-grade centralized exception handler for the entire REST API surface.
 *
 * <h2>Design principles</h2>
 * <ul>
 *   <li><strong>No stack trace leakage</strong> — the {@code Exception} catch-all logs
 *       the full trace server-side but returns only a generic user-facing message.</li>
 *   <li><strong>No null leakage</strong> — every message extraction is guarded via
 *       {@link #safeMessage(Exception)} so that a {@code null} exception message never
 *       reaches the JSON response.</li>
 *   <li><strong>Single {@code buildErrorResponse} helper</strong> — all handlers
 *       delegate to one overloaded method. HTTP status, message, path and timestamp
 *       are always populated; {@code validationErrors} defaults to an empty list.</li>
 *   <li><strong>Multi-exception grouping</strong> — semantically equivalent exceptions
 *       share a single {@code @ExceptionHandler} method rather than duplicating logic.</li>
 *   <li><strong>Dynamic status resolution for external services</strong> — the Python
 *       scheduling service error code is translated to an HTTP status via
 *       {@link #resolveHttpStatusForPythonError(String)} instead of hard-coding
 *       conditions in individual methods.</li>
 * </ul>
 *
 * <h2>Extending this handler</h2>
 * <ol>
 *   <li>Add a message constant to {@link schedule.example.schedule.config.Messages}.</li>
 *   <li>Create a typed {@link RuntimeException} subclass in this package.</li>
 *   <li>Add an {@code @ExceptionHandler} method here (or include the new type in an
 *       existing multi-exception handler if the HTTP status is the same).</li>
 * </ol>
 *
 * <h2>Note on Spring Security exceptions</h2>
 * {@link AuthenticationException} and {@link AccessDeniedException} are normally
 * intercepted by {@link schedule.example.schedule.security.RestAuthenticationEntryPoint}
 * and {@link schedule.example.schedule.security.RestAccessDeniedHandler} respectively,
 * before reaching this advice. The handlers below serve as a safety net for cases where
 * these exceptions escape the filter chain (e.g. thrown programmatically from a service
 * inside a controller method).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/**
	 * Static mapping from Python service error codes to HTTP statuses.
	 * Defined once at class-load time; zero allocation, O(1) lookup.
	 */
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

	// =========================================================================
	// 404 NOT FOUND
	// =========================================================================

	/**
	 * Handles all resource-not-found exceptions. Multiple types share this handler
	 * because they all map to 404 with a pre-formatted message.
	 */
	@ExceptionHandler({
		NotFoundException.class,
		ExamNotFoundException.class,
		SettingsNotFoundException.class
	})
	public ResponseEntity<ApiErrorResponse> handleNotFound(RuntimeException ex, HttpServletRequest request) {
		return buildErrorResponse(HttpStatus.NOT_FOUND, safeMessage(ex), request);
	}

	// =========================================================================
	// 409 CONFLICT
	// =========================================================================

	/**
	 * Handles duplicate-resource and conflict exceptions. Both signal a state
	 * collision and must return 409 — grouping them avoids duplication.
	 */
	@ExceptionHandler({ConflictException.class, DuplicateResourceException.class})
	public ResponseEntity<ApiErrorResponse> handleConflict(RuntimeException ex, HttpServletRequest request) {
		return buildErrorResponse(HttpStatus.CONFLICT, safeMessage(ex), request);
	}

	// =========================================================================
	// 400 BAD REQUEST — simple (no per-field breakdown)
	// =========================================================================

	/**
	 * Handles simple bad-request scenarios that do not produce per-field error lists.
	 *
	 * <ul>
	 *   <li>{@link ValidationException} — domain-level format/value rejection</li>
	 *   <li>{@link MethodArgumentTypeMismatchException} — path/query param type mismatch</li>
	 *   <li>{@link ConstraintViolationException} — {@code @Validated} method parameter violations</li>
	 * </ul>
	 */
	@ExceptionHandler({
		ValidationException.class,
		MethodArgumentTypeMismatchException.class,
		ConstraintViolationException.class
	})
	public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
		return buildErrorResponse(HttpStatus.BAD_REQUEST, badRequestMessage(ex), request);
	}

	// =========================================================================
	// 400 BAD REQUEST — JSON parse error
	// =========================================================================

	/**
	 * Handles malformed or unreadable JSON request bodies.
	 *
	 * <p>The underlying cause is intentionally not forwarded to the client to avoid
	 * leaking Jackson internals. A short, user-friendly message is returned instead,
	 * while the cause is logged at DEBUG level to aid development.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleJsonParseError(
		HttpMessageNotReadableException ex,
		HttpServletRequest request
	) {
		log.debug("JSON deserialization failure for {} {}: {}",
			request.getMethod(), request.getRequestURI(), ex.getMessage());
		return buildErrorResponse(HttpStatus.BAD_REQUEST, Messages.HTTP_JSON_PARSE_ERROR, request);
	}

	// =========================================================================
	// 400 BAD REQUEST — bean validation (with per-field breakdown)
	// =========================================================================

	/**
	 * Handles {@code @Valid} / {@code @Validated} failures on request body DTOs.
	 * Produces a structured list of {@link FieldValidationError}s so clients can
	 * highlight individual form fields.
	 */
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

	// =========================================================================
	// 405 METHOD NOT ALLOWED
	// =========================================================================

	/**
	 * Handles requests that use an HTTP method not supported by the matched endpoint
	 * (e.g. DELETE on a read-only resource).
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
		HttpRequestMethodNotSupportedException ex,
		HttpServletRequest request
	) {
		log.debug("Method not allowed: {} {} — supported: {}",
			ex.getMethod(), request.getRequestURI(), ex.getSupportedHttpMethods());
		return buildErrorResponse(HttpStatus.METHOD_NOT_ALLOWED, Messages.HTTP_METHOD_NOT_SUPPORTED, request);
	}

	// =========================================================================
	// 401 UNAUTHORIZED — authentication & token failures
	// =========================================================================

	/**
	 * Handles authentication failures that escape the Spring Security filter chain
	 * and token-level validation exceptions thrown from service/controller code.
	 *
	 * <ul>
	 *   <li>{@link AuthenticationException} — Spring Security authentication failure</li>
	 *   <li>{@link InvalidTokenException} — custom typed token-validation failure</li>
	 * </ul>
	 */
	@ExceptionHandler({AuthenticationException.class, InvalidTokenException.class})
	public ResponseEntity<ApiErrorResponse> handleAuthentication(Exception ex, HttpServletRequest request) {
		return buildErrorResponse(HttpStatus.UNAUTHORIZED, safeMessage(ex), request);
	}

	// =========================================================================
	// 403 FORBIDDEN — authorization failures
	// =========================================================================

	/**
	 * Handles authorization rejections that fall through to the controller layer.
	 *
	 * <ul>
	 *   <li>{@link AccessDeniedException} — Spring Security role/authority check</li>
	 *   <li>{@link UnauthorizedException} — business-domain access control</li>
	 * </ul>
	 */
	@ExceptionHandler({AccessDeniedException.class, UnauthorizedException.class})
	public ResponseEntity<ApiErrorResponse> handleForbidden(Exception ex, HttpServletRequest request) {
		return buildErrorResponse(HttpStatus.FORBIDDEN, safeMessage(ex), request);
	}

	// =========================================================================
	// 422 UNPROCESSABLE ENTITY — business rule violations
	// =========================================================================

	/**
	 * Handles semantically invalid operations that pass structural validation but
	 * violate domain business rules. 422 is preferred over 400 here because the
	 * request is well-formed; the server simply cannot process it in the current state.
	 *
	 * <ul>
	 *   <li>{@link BusinessRuleViolationException} — generic rule violation</li>
	 *   <li>{@link InvalidExamTimeException} — exam time ordering/range violation</li>
	 *   <li>{@link InvigilatorUnavailableException} — personnel availability conflict</li>
	 * </ul>
	 */
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

	// =========================================================================
	// 429 TOO MANY REQUESTS — rate limiting
	// =========================================================================

	/**
	 * Handles service-layer rate-limit violations. The {@code Retry-After} header is
	 * not set here because the retry delay is context-dependent; callers that need it
	 * should extend this handler or use a {@link org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice}.
	 */
	@ExceptionHandler(RateLimitExceededException.class)
	public ResponseEntity<ApiErrorResponse> handleRateLimitExceeded(
		RateLimitExceededException ex,
		HttpServletRequest request
	) {
		return buildErrorResponse(HttpStatus.TOO_MANY_REQUESTS, safeMessage(ex), request);
	}

	// =========================================================================
	// DYNAMIC STATUS — external Python scheduling service
	// =========================================================================

	/**
	 * Handles all failures originating from the external Python scheduling micro-service.
	 *
	 * <p>The HTTP status is resolved dynamically from the exception's {@code errorCode}
	 * field using {@link #resolveHttpStatusForPythonError(String)} rather than
	 * hard-coding status checks here. This keeps the handler clean regardless of how
	 * many error codes the service introduces in the future.
	 *
	 * <p>Errors are logged at WARN because they indicate a dependency issue rather than
	 * a programming error in this service.
	 */
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

	// =========================================================================
	// 500 INTERNAL SERVER ERROR — catch-all
	// =========================================================================

	/**
	 * Safety-net handler for any exception not matched by a more specific handler above.
	 *
	 * <p><strong>Security:</strong> the full stack trace is logged server-side but
	 * only a generic, non-revealing message is returned to the caller. This prevents
	 * accidental disclosure of internal class names, SQL, or file paths.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
		return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, Messages.GENERAL_UNEXPECTED_ERROR, request);
	}

	// =========================================================================
	// HELPER METHODS
	// =========================================================================

	/**
	 * Builds an {@link ApiErrorResponse} without per-field validation errors.
	 *
	 * @param status  the HTTP status to return
	 * @param message the user-facing error message
	 * @param request the current servlet request (for path extraction)
	 * @return a fully-populated response entity
	 */
	private ResponseEntity<ApiErrorResponse> buildErrorResponse(
		HttpStatus status,
		String message,
		HttpServletRequest request
	) {
		return buildErrorResponse(status, message, request, List.of());
	}

	/**
	 * Core response builder. All handler methods must delegate here to guarantee a
	 * consistent response envelope across the entire API.
	 *
	 * @param status           the HTTP status to return
	 * @param message          the user-facing error message
	 * @param request          the current servlet request
	 * @param validationErrors per-field validation errors (empty list when not applicable)
	 * @return a fully-populated response entity
	 */
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

	/**
	 * Safely extracts the exception message, substituting a generic fallback when the
	 * message is {@code null} to prevent {@code "null"} strings in API responses.
	 *
	 * @param ex the exception whose message is needed
	 * @return a non-null, non-empty message string
	 */
	private String safeMessage(Exception ex) {
		return Objects.requireNonNullElse(ex.getMessage(), Messages.GENERAL_UNEXPECTED_ERROR);
	}

	/**
	 * Resolves the user-facing message for simple 400 Bad Request scenarios.
	 *
	 * <ul>
	 *   <li>For {@link MethodArgumentTypeMismatchException}: names the offending parameter.</li>
	 *   <li>For {@link ConstraintViolationException}: concatenates all constraint messages.</li>
	 *   <li>For everything else: delegates to {@link #safeMessage(Exception)}.</li>
	 * </ul>
	 */
	private String badRequestMessage(Exception ex) {
		if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
			// getName() is guaranteed non-null by the Spring framework contract
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

	/**
	 * Translates a {@link PythonServiceException} error code to the appropriate HTTP
	 * status using the static {@link #PYTHON_ERROR_CODE_STATUS_MAP}. Unmapped codes
	 * fall back to {@code 500 Internal Server Error} to avoid exposing ambiguous
	 * semantics to callers.
	 *
	 * @param errorCode the code carried by {@link PythonServiceException}
	 * @return the resolved {@link HttpStatus}; never {@code null}
	 */
	private HttpStatus resolveHttpStatusForPythonError(String errorCode) {
		if (errorCode == null) {
			return HttpStatus.INTERNAL_SERVER_ERROR;
		}
		return PYTHON_ERROR_CODE_STATUS_MAP.getOrDefault(errorCode, HttpStatus.INTERNAL_SERVER_ERROR);
	}
}

