package schedule.example.schedule.exception;

import schedule.example.schedule.config.Messages;

/**
 * Thrown when an operation attempts to create or rename a resource in a way that
 * would violate a uniqueness constraint — for example, inserting a person whose name
 * already exists, or creating a room with a duplicate code.
 *
 * <p>This is the generic counterpart to the domain-specific conflict messages already
 * defined in {@link schedule.example.schedule.config.Messages} (e.g.
 * {@code PERSON_NAME_EXISTS}, {@code ROOM_NAME_EXISTS}). Prefer those specific
 * messages when available; use this class for situations not covered by a dedicated
 * constant.
 *
 * <p>Maps to HTTP 409 Conflict via {@link GlobalExceptionHandler}.
 */
public class DuplicateResourceException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception with the given detailed message.
	 *
	 * @param message a user-facing description of the duplicate (reference
	 *                {@link schedule.example.schedule.config.Messages} constants)
	 */
	public DuplicateResourceException(String message) {
		super(message);
	}

	/**
	 * Creates an exception for a duplicate of a named resource.
	 *
	 * <p>Example: {@code new DuplicateResourceException("Room", "LAB-01")}
	 * produces "Room 'LAB-01' already exists."
	 *
	 * @param resourceType human-readable resource type (e.g. "Room", "Person")
	 * @param identifier   the value that already exists
	 */
	public DuplicateResourceException(String resourceType, String identifier) {
		super("'%s' with identifier '%s' already exists.".formatted(resourceType, identifier));
	}
}

