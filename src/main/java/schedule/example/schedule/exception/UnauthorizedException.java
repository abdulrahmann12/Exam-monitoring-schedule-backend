package schedule.example.schedule.exception;

import schedule.example.schedule.config.Messages;

/**
 * Thrown when an authenticated user attempts a business-level operation they are not
 * permitted to perform — distinct from Spring Security's {@code AccessDeniedException}
 * which covers infrastructure-level role/authority checks.
 *
 * <p>Use this exception inside service methods that have business-domain authorization
 * logic (e.g. "only the owner may modify this resource") rather than relying solely on
 * method-security annotations.
 *
 * <p>Maps to HTTP 403 Forbidden via {@link GlobalExceptionHandler}.
 */
public class UnauthorizedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception with the standard access-denied message.
	 */
	public UnauthorizedException() {
		super(Messages.SECURITY_ACCESS_DENIED);
	}

	/**
	 * Creates an exception with a specific explanation of why access was denied.
	 *
	 * @param message a user-facing explanation (must not expose internal details)
	 */
	public UnauthorizedException(String message) {
		super(message);
	}
}

