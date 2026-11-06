package schedule.example.schedule.service;

/**
 * An email could not be handed to the email provider.
 *
 * @see #retryable() true for temporary problems (provider down, timeout, rate limit) — try again later;
 *      false for problems that will not go away (invalid address, rejected sender, bad request)
 */
public class MailDeliveryException extends IllegalStateException {

	private final boolean retryable;

	public MailDeliveryException(String message, boolean retryable, Throwable cause) {
		super(message, cause);
		this.retryable = retryable;
	}

	public boolean retryable() {
		return retryable;
	}
}
