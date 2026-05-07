package schedule.example.schedule.exception;

import schedule.example.schedule.config.Messages;

/**
 * Thrown when application-level {@link schedule.example.schedule.entity.Settings} have
 * not been initialized or cannot be found in the database.
 *
 * <p>This is distinct from a generic {@link NotFoundException} because missing settings
 * usually indicate a deployment/initialization problem rather than a user error, and
 * it may warrant a dedicated alert in monitoring systems.
 *
 * <p>Maps to HTTP 404 Not Found via {@link GlobalExceptionHandler}.
 */
public class SettingsNotFoundException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception with the standard settings-not-configured message.
	 */
	public SettingsNotFoundException() {
		super(Messages.SETTINGS_NOT_FOUND);
	}

	/**
	 * Creates an exception with a customized message when more context is available.
	 *
	 * @param message additional context (e.g. which settings key is missing)
	 */
	public SettingsNotFoundException(String message) {
		super(message);
	}
}

