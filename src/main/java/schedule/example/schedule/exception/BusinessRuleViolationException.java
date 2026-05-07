package schedule.example.schedule.exception;

import schedule.example.schedule.config.Messages;

/**
 * Thrown when a requested operation is structurally valid but violates a domain
 * business rule that cannot be expressed as a simple field-level validation constraint.
 *
 * <p>Examples:
 * <ul>
 *   <li>Attempting to delete a time slot that still has active assignments</li>
 *   <li>Assigning a chief invigilator beyond the configured room-per-slot limit</li>
 *   <li>Changing a person's role while they hold incompatible active assignments</li>
 * </ul>
 *
 * <p>Maps to HTTP 422 Unprocessable Entity via {@link GlobalExceptionHandler}.
 * Use 422 (rather than 400) to signal that the request was well-formed but the server
 * cannot process it due to semantic errors.
 */
public class BusinessRuleViolationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception with a default generic message.
	 * Prefer the {@link #BusinessRuleViolationException(String)} constructor with a
	 * domain-specific message sourced from {@link Messages}.
	 */
	public BusinessRuleViolationException() {
		super(Messages.BUSINESS_RULE_VIOLATION);
	}

	/**
	 * Creates an exception with a specific, user-facing description of the violated rule.
	 *
	 * @param message description of the violated business constraint
	 */
	public BusinessRuleViolationException(String message) {
		super(message);
	}
}

