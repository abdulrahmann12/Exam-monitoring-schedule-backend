package schedule.example.schedule.exception;

import schedule.example.schedule.config.Messages;

/**
 * Thrown when a caller exceeds a configured request-rate threshold.
 *
 * <p>In the current implementation, the {@link schedule.example.schedule.security.LoginRateLimiterFilter}
 * short-circuits directly through the servlet response rather than throwing this exception.
 * This class exists so that service-layer or programmatic rate-limit checks (e.g. on
 * bulk-import endpoints or scheduling solver invocations) can produce a typed exception
 * that the {@link GlobalExceptionHandler} maps to a proper 429 response with
 * Retry-After context.
 *
 * <p>Maps to HTTP 429 Too Many Requests via {@link GlobalExceptionHandler}.
 */
public class RateLimitExceededException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception with the standard rate-limit message.
	 */
	public RateLimitExceededException() {
		super(Messages.RATE_LIMIT_EXCEEDED);
	}

	/**
	 * Creates an exception with a message that may include a retry hint.
	 *
	 * @param message user-facing message, e.g. "Rate limit exceeded. Try again in 30 seconds."
	 */
	public RateLimitExceededException(String message) {
		super(message);
	}
}

