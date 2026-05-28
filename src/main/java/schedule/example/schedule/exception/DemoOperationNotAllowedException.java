package schedule.example.schedule.exception;

/**
 * Thrown when a demo-mode user attempts an operation that is not permitted in demo mode.
 *
 * <p>Maps to {@code 403 Forbidden} via {@link GlobalExceptionHandler}.
 */
public class DemoOperationNotAllowedException extends RuntimeException {

    public DemoOperationNotAllowedException() {
        super("Operation disabled in demo mode.");
    }

    public DemoOperationNotAllowedException(String message) {
        super(message);
    }
}

