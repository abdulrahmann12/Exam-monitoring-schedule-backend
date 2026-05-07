package schedule.example.schedule.exception;

import schedule.example.schedule.config.Messages;

/**
 * Thrown when communication with the external Python scheduling micro-service fails.
 *
 * <p>Carries an {@code errorCode} string that the {@link GlobalExceptionHandler}
 * uses to perform a dynamic mapping to an HTTP status code. This allows the handler
 * to return context-appropriate statuses (502 Bad Gateway for connectivity issues,
 * 503 Service Unavailable, 504 Gateway Timeout, etc.) without hard-coding the
 * mapping in every call site.
 *
 * <h3>Supported error codes and their HTTP status mappings</h3>
 * <pre>
 * PYTHON_400  →  400 Bad Request         (invalid payload sent to service)
 * PYTHON_401  →  401 Unauthorized        (service-to-service auth failure)
 * PYTHON_403  →  403 Forbidden           (operation not permitted)
 * PYTHON_404  →  404 Not Found           (resource missing on service side)
 * PYTHON_409  →  409 Conflict            (optimistic lock / state conflict)
 * PYTHON_422  →  422 Unprocessable Entity(business rule rejection)
 * PYTHON_429  →  429 Too Many Requests   (service-side rate limit)
 * PYTHON_500  →  500 Internal Server Error
 * PYTHON_502  →  502 Bad Gateway         (service returned invalid response)
 * PYTHON_503  →  503 Service Unavailable (service is down)
 * PYTHON_504  →  504 Gateway Timeout     (service did not respond in time)
 * </pre>
 *
 * <p>Maps to an HTTP status resolved at runtime by {@link GlobalExceptionHandler}.
 */
public class PythonServiceException extends RuntimeException {

	@java.io.Serial
	private static final long serialVersionUID = 1L;

	private final String errorCode;

	/**
	 * Creates an exception with a known error code (see class Javadoc for valid codes)
	 * and a user-facing message sourced from {@link Messages}.
	 *
	 * @param errorCode one of the {@code PYTHON_*} codes defined above
	 * @param message   user-facing description (reference {@link Messages} constants)
	 */
	public PythonServiceException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	/**
	 * Creates an exception wrapping the original low-level cause, useful when
	 * catching HTTP client exceptions and translating them.
	 *
	 * @param errorCode one of the {@code PYTHON_*} codes defined above
	 * @param message   user-facing description
	 * @param cause     the underlying exception — never surfaced in API responses
	 */
	public PythonServiceException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	/**
	 * Convenience factory for a generic unavailable-service scenario.
	 *
	 * @return a {@code PYTHON_503} exception with the standard unavailable message
	 */
	public static PythonServiceException unavailable() {
		return new PythonServiceException("PYTHON_503", Messages.PYTHON_SERVICE_UNAVAILABLE);
	}

	/**
	 * Convenience factory for a gateway-timeout scenario.
	 *
	 * @return a {@code PYTHON_504} exception with the standard timeout message
	 */
	public static PythonServiceException timeout() {
		return new PythonServiceException("PYTHON_504", Messages.PYTHON_SERVICE_TIMEOUT);
	}

	/**
	 * Convenience factory for a bad-request sent to the service.
	 *
	 * @param detail extra context appended to the standard message
	 * @return a {@code PYTHON_400} exception
	 */
	public static PythonServiceException badRequest(String detail) {
		return new PythonServiceException("PYTHON_400", Messages.PYTHON_SERVICE_BAD_REQUEST + " Detail: " + detail);
	}

	/** @return the error code used by {@link GlobalExceptionHandler} for HTTP-status resolution */
	public String getErrorCode() {
		return errorCode;
	}
}

