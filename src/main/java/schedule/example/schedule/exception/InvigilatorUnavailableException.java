package schedule.example.schedule.exception;

import schedule.example.schedule.config.Messages;

import java.text.MessageFormat;

/**
 * Thrown when an invigilator is assigned to an exam slot but their recorded
 * availability does not cover that slot's date/period.
 *
 * <p>This is a domain-specific specialization of {@link BusinessRuleViolationException}
 * kept separate so that call sites can catch it precisely and logging/metrics can
 * differentiate it from generic rule violations.
 *
 * <p>Maps to HTTP 422 Unprocessable Entity via {@link GlobalExceptionHandler}.
 */
public class InvigilatorUnavailableException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception for a named invigilator who is unavailable.
	 *
	 * @param invigilatorName the display name of the invigilator
	 */
	public InvigilatorUnavailableException(String invigilatorName) {
		super(MessageFormat.format(Messages.INVIGILATOR_UNAVAILABLE, invigilatorName));
	}

	/**
	 * Creates an exception with a fully pre-formatted message — for example when the
	 * caller already resolved a more detailed message via
	 * {@link schedule.example.schedule.config.MessageResolver}.
	 *
	 * @param invigilatorName the invigilator's display name
	 * @param date            the date of unavailability (string representation)
	 * @param period          the period/shift label
	 */
	public InvigilatorUnavailableException(String invigilatorName, String date, String period) {
		super(MessageFormat.format(Messages.ASSIGNMENT_INVIGILATOR_UNAVAILABLE, invigilatorName, date, period));
	}
}

