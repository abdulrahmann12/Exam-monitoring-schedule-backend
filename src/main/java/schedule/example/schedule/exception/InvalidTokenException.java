package schedule.example.schedule.exception;

import schedule.example.schedule.config.Messages;

/**
 * Thrown when a JWT or other authentication token is structurally invalid, has a bad
 * signature, or has expired.
 *
 * <p>In the normal flow, token validation happens in {@link schedule.example.schedule.security.JwtAuthenticationFilter}
 * and failures are forwarded to {@link schedule.example.schedule.security.RestAuthenticationEntryPoint}.
 * This exception exists as a typed representation for service-layer or programmatic
 * token validation that occurs after the filter chain (e.g. token refresh, API-key
 * validation, or internal service calls).
 *
 * <p>Maps to HTTP 401 Unauthorized via {@link GlobalExceptionHandler}.
 */
public class InvalidTokenException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception with the standard "invalid token" message.
	 */
	public InvalidTokenException() {
		super(Messages.SECURITY_INVALID_TOKEN);
	}

	/**
	 * Creates an exception with a specific reason — for example "Token has expired" or
	 * "Signature verification failed".
	 *
	 * @param message user-facing detail (must not include internal stack or secret data)
	 */
	public InvalidTokenException(String message) {
		super(message);
	}

	/**
	 * Creates an exception that wraps a lower-level cause (e.g. a {@link io.jsonwebtoken.JwtException}).
	 *
	 * @param message user-facing detail
	 * @param cause   the underlying exception — never surfaced in API responses
	 */
	public InvalidTokenException(String message, Throwable cause) {
		super(message, cause);
	}
}

