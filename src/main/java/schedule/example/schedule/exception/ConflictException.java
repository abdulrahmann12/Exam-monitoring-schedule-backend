package schedule.example.schedule.exception;

public class ConflictException extends RuntimeException {

	public ConflictException(String message) {
		super(message);
	}
}