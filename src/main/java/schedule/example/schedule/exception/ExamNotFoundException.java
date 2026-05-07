package schedule.example.schedule.exception;

import schedule.example.schedule.config.Messages;

import java.text.MessageFormat;

/**
 * Thrown when an exam entity cannot be found by the given identifier.
 *
 * <p>Maps to HTTP 404 Not Found via {@link GlobalExceptionHandler}.
 */
public class ExamNotFoundException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception for a missing exam with the given id.
	 *
	 * @param id the identifier that was looked up
	 */
	public ExamNotFoundException(long id) {
		super(MessageFormat.format(Messages.EXAM_NOT_FOUND, id));
	}

	/**
	 * Creates an exception with a fully-formed custom message (e.g. when the caller
	 * already resolved the message via {@link schedule.example.schedule.config.MessageResolver}).
	 *
	 * @param message the pre-formatted error message
	 */
	public ExamNotFoundException(String message) {
		super(message);
	}
}

