package schedule.example.schedule.exception;

import schedule.example.schedule.config.Messages;

/**
 * Thrown when an exam's time configuration is logically invalid — for example when the
 * end time precedes the start time, or when the exam window falls outside the assigned
 * time slot boundaries.
 *
 * <p>Maps to HTTP 422 Unprocessable Entity via {@link GlobalExceptionHandler}.
 */
public class InvalidExamTimeException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception using the default "end time must be after start time" message.
	 */
	public InvalidExamTimeException() {
		super(Messages.EXAM_TIME_INVALID);
	}

	/**
	 * Creates an exception with a specific message, e.g. when the exam falls outside
	 * the slot boundaries.
	 *
	 * @param message the detailed reason
	 */
	public InvalidExamTimeException(String message) {
		super(message);
	}
}

