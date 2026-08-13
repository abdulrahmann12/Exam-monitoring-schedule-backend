package schedule.example.schedule.config;

/**
 * Centralized message constants for all application layers.
 *
 * <p><strong>Organization:</strong> constants are grouped by functional domain so that
 * every layer (services, validators, exception classes) can reference a single source of
 * truth. No hardcoded strings should appear anywhere outside this class.
 *
 * <p><strong>Extending:</strong> add a new category block with a descriptive section
 * header comment, following the existing naming convention
 * {@code DOMAIN_CONTEXT_DETAIL}.
 */
public final class Messages {

	private Messages() {}

	// ==================== General Messages ====================
	public static final String GENERAL_UNEXPECTED_ERROR = "An unexpected error occurred. Please try again later.";

	// ===================== Database / Persistence Messages ====================
	public static final String DATABASE_UNAVAILABLE =
			"Database service is currently unavailable. Please try again later.";
	// ==================== Security / JWT Messages ====================
	/** Returned when the JWT signature is invalid or the token is structurally malformed. */
	public static final String SECURITY_INVALID_TOKEN = "The provided authentication token is invalid.";
	/** Returned when a valid JWT has passed its expiry date. */
	public static final String SECURITY_EXPIRED_TOKEN = "The authentication token has expired. Please log in again.";
	/** Returned when an authenticated user attempts to access a resource they are not authorized for. */
	public static final String SECURITY_ACCESS_DENIED = "You do not have permission to access this resource.";
	/** Returned when a protected endpoint is accessed without any authentication credentials. */
	public static final String SECURITY_AUTHENTICATION_REQUIRED = "Authentication is required to access this resource.";

	// ==================== HTTP Messages ====================
	/** Returned for 405 Method Not Allowed. */
	public static final String HTTP_METHOD_NOT_SUPPORTED = "The HTTP method used is not supported for this endpoint.";
	/** Returned when the request body cannot be deserialized (malformed JSON, wrong types, etc.). */
	public static final String HTTP_JSON_PARSE_ERROR = "The request body contains invalid or malformed JSON. Please verify the format and field types.";

	// ==================== Validation Messages ====================
	/** Top-level message for 400 responses that carry per-field validation errors. */
	public static final String VALIDATION_FAILED = "Validation failed. Please check the highlighted fields and try again.";
	/** Fallback message for a field error that provides no message of its own. */
	public static final String VALIDATION_FIELD_INVALID = "Invalid value.";
	/** Used when a path/query parameter cannot be converted to its target type. Accepts the parameter name as argument 0. */
	public static final String VALIDATION_INVALID_PARAMETER = "Invalid value for parameter '%s'.";

	// ==================== Rate Limit Messages ====================
	/** Returned as 429 Too Many Requests. */
	public static final String RATE_LIMIT_EXCEEDED = "Too many requests. Please slow down and try again later.";

	// ==================== Python / Scheduling Service Messages ====================
	/** Generic error when the Python scheduling micro-service returns a failure. */
	public static final String PYTHON_SERVICE_ERROR = "The scheduling service returned an error. Please contact support if the problem persists.";
	/** Returned when the scheduling service is unreachable or returns 503. */
	public static final String PYTHON_SERVICE_UNAVAILABLE = "The scheduling service is currently unavailable. Please try again later.";
	/** Returned when the scheduling service call exceeds the configured timeout. */
	public static final String PYTHON_SERVICE_TIMEOUT = "The scheduling service did not respond in time. Please try again later.";
	/** Returned when the scheduling service rejects the request payload. */
	public static final String PYTHON_SERVICE_BAD_REQUEST = "The scheduling service rejected the request due to invalid input.";

	// ==================== Exam Messages ====================
	/** Resource-not-found for an exam entity. Accepts the exam id as argument 0. */
	public static final String EXAM_NOT_FOUND = "Exam with id {0} was not found.";
	/** Returned when an exam's end time is not strictly after its start time. */
	public static final String EXAM_TIME_INVALID = "Exam end time must be after the start time.";
	/** Returned when exam times fall outside the bounds of the assigned time slot. */
	public static final String EXAM_TIME_OUTSIDE_SLOT = "Exam time is outside the bounds of the assigned time slot.";

	// ==================== Settings Messages ====================
	/** Returned when application-level settings have not been initialized. */
	public static final String SETTINGS_NOT_FOUND = "Application settings have not been configured. Please contact an administrator.";

	// ==================== Duplicate Resource Messages ====================
	/** Generic duplicate-resource message when no domain-specific one exists. */
	public static final String RESOURCE_ALREADY_EXISTS = "A resource with the same unique identifier already exists.";

	// ==================== Business Rule Messages ====================
	/** Generic message for a business rule violation that has its own custom description. */
	public static final String BUSINESS_RULE_VIOLATION = "The requested operation violates a business constraint.";
	/** Invigilator-specific unavailability message. Accepts invigilator name as arg 0. */
	public static final String INVIGILATOR_UNAVAILABLE = "Invigilator ''{0}'' is unavailable for the requested time slot.";

	// ==================== Pagination Messages ====================
	public static final String PAGINATION_INVALID_PAGE = "Page index must be zero or greater.";
	public static final String PAGINATION_INVALID_SIZE = "Page size must be between 1 and {0}.";
	public static final String PAGINATION_INVALID_SORT = "Sort field ''{0}'' is not allowed. Allowed values: {1}.";

	// ==================== Auth Messages ====================
	public static final String AUTH_INVALID_CREDENTIALS = "Invalid email or password.";

	// ==================== Demo Mode Messages ====================
	/** Returned when a demo user attempts a blocked destructive operation. */
	public static final String DEMO_OPERATION_NOT_ALLOWED = "Operation disabled in demo mode.";

	// ==================== Person Messages ====================
	public static final String PERSON_NOT_FOUND = "Person with id {0} was not found.";
	public static final String PERSON_NAME_EXISTS = "Person ''{0}'' already exists.";
	public static final String PERSON_DELETE_IN_USE = "Person ''{0}'' is referenced by existing assignments and cannot be deleted.";
	public static final String PERSON_ROLE_CHANGE_CHIEF_IN_USE = "Person ''{0}'' is currently assigned as a chief invigilator and cannot change to a non-chief role.";
	public static final String PERSON_ROLE_CHANGE_INVIGILATOR_IN_USE = "Person ''{0}'' is currently assigned as an invigilator and cannot change to a non-invigilator role.";
	public static final String PERSON_AVAILABILITY_CONFLICT = "Updated availability does not cover an existing assignment on {0} ({1}).";
	public static final String PERSON_EMAIL_EXISTS = "A person with email ''{0}'' already exists.";

	// ==================== Schedule Group Messages ====================
	public static final String SCHEDULE_GROUP_NAME_EXISTS = "Schedule group ''{0}'' already exists.";
	public static final String SCHEDULE_GROUP_NOT_FOUND = "Schedule group with id {0} was not found.";
	public static final String SCHEDULE_GROUP_DELETE_LAST = "The last schedule group cannot be deleted.";

	// ==================== Room Messages ====================
	public static final String ROOM_NOT_FOUND = "Room with id {0} was not found.";
	public static final String ROOM_NAME_EXISTS = "Room ''{0}'' already exists.";
	public static final String ROOM_DELETE_IN_USE = "Room ''{0}'' is referenced by existing assignments and cannot be deleted.";

	// ==================== Time Slot Messages ====================
	public static final String SLOT_NOT_FOUND = "Time slot with id {0} was not found.";
	public static final String SLOT_INACTIVE = "Time slot is inactive and cannot be used for new assignments.";
	public static final String SLOT_DELETE_IN_USE = "Time slot ''{0}'' at {1} is referenced by existing assignments and cannot be deleted. Use deactivate instead.";
	public static final String SLOT_INVALID_RANGE = "End time must be after start time.";
	public static final String SLOT_CANNOT_CHANGE_GROUP = "A time slot cannot be moved to a different schedule group.";

	// ==================== Assignment Messages ====================
	public static final String ASSIGNMENT_NOT_FOUND = "Assignment with id {0} was not found.";
	public static final String ASSIGNMENT_SLOT_DATE_REQUIRED = "Assignments require a time slot with a concrete date.";
	public static final String ASSIGNMENT_DUPLICATE_ROOM_SLOT = "Room ''{0}'' already has an assignment for the selected time slot.";
	public static final String ASSIGNMENT_CHIEF_ROLE_INVALID = "Person ''{0}'' is not a chief invigilator.";
	public static final String ASSIGNMENT_INVIGILATOR_ROLE_INVALID = "Person ''{0}'' is not an invigilator.";
	public static final String ASSIGNMENT_INVIGILATOR_DUPLICATE = "Duplicate invigilator ids are not allowed in the same room assignment.";
	public static final String ASSIGNMENT_BULK_EMPTY = "At least one room assignment is required for bulk save.";
	public static final String ASSIGNMENT_BULK_DUPLICATE_ROOM_SLOT = "Duplicate bulk assignment for room {0}, slot {1}, and date {2}.";
	public static final String ASSIGNMENT_CHIEF_LIMIT = "Chief invigilator ''{0}'' is already supervising the maximum number of rooms at an overlapping time.";
	public static final String ASSIGNMENT_INVIGILATOR_DOUBLE_BOOKED = "Invigilator ''{0}'' is already assigned at an overlapping time.";
	public static final String ASSIGNMENT_CHIEF_UNAVAILABLE = "Chief invigilator ''{0}'' is unavailable on {1} ({2}).";
	public static final String ASSIGNMENT_INVIGILATOR_UNAVAILABLE = "Invigilator ''{0}'' is unavailable on {1} ({2}).";
	public static final String ASSIGNMENT_SLOT_GROUP_MISMATCH = "Time slot does not belong to schedule group ''{0}''.";
	public static final String ASSIGNMENT_BULK_MIXED_GROUPS = "Bulk save cannot mix assignments from different schedule groups.";
	public static final String ASSIGNMENT_GROUP_REQUIRED = "Assignment is missing a schedule group.";
}

