package com.uniguard.schedule.config;

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
	public static final String AUTH_CURRENT_PASSWORD_WRONG = "Current password is incorrect.";
	public static final String MAIL_NOT_CONFIGURED = "Email sending is not configured. Set BREVO_API_KEY and BREVO_SENDER_EMAIL on the server.";
	public static final String AUTH_ACCOUNT_LOCKED = "This account is locked after too many failed sign-in attempts. Try again after {0}, or ask your administrator to reset it.";
	public static final String EMAIL_CHANGE_SAME = "This is already your sign-in email.";
	public static final String EMAIL_CHANGE_NO_MAIL = "Email is not set up on the server, so the new address cannot be confirmed. Ask the server administrator to set up email first.";
	public static final String PROFILE_NAME_INVALID = "Enter a name of 1 to 120 characters.";
	public static final String AUTH_PASSWORD_UNCHANGED = "The new password must be different from the current one.";
	public static final String AUTH_PASSWORD_TOO_SHORT = "Password must be at least {0} characters.";
	public static final String AUTH_PASSWORD_TOO_LONG = "Password is too long (maximum {0} bytes; non-Latin letters count as 2 or 3).";
	public static final String AUTH_ACCOUNT_DISABLED = "This account is disabled. Ask your administrator to enable it.";
	public static final String AUTH_PASSWORD_CHANGE_REQUIRED = "You must change your password before continuing.";
	public static final String AUTH_SESSION_ENDED = "Your session has ended. Please sign in again.";
	public static final String AUTH_SESSION_RENEWED_ELSEWHERE = "Your session was just renewed in another tab. Please retry.";
	public static final String AUTH_CSRF_REJECTED = "Request blocked: it did not come from the application.";
	public static final String AUTH_LINK_INVALID = "This link is invalid, already used or expired. Ask for a new one.";
	public static final String AUTH_RESET_REQUESTED = "If an account exists for this email, we have sent a link to reset the password. Check your inbox (and spam folder).";
	public static final String AUTH_TOO_MANY_RESET_REQUESTS = "Too many requests. Please wait a few minutes and try again.";
	public static final String AUTH_UNIVERSITY_INACTIVE = "Access for your university is currently paused. Please contact support.";
	public static final String AUTH_ACCESS_DENIED = "You do not have permission to do this.";

	// ==================== User Management Messages ====================
	public static final String USER_NOT_FOUND = "User not found.";
	public static final String USER_EMAIL_EXISTS = "A user with this email already exists.";
	public static final String USER_ROLE_NOT_ASSIGNABLE = "This role cannot be assigned.";
	public static final String USER_DEMO_ACCOUNT_PROTECTED = "The demo account is managed by the system and cannot be changed here.";
	public static final String USER_CANNOT_CHANGE_SELF = "You cannot change your own role, disable or delete your own account. Ask another administrator.";
	public static final String USER_RESET_OWN_PASSWORD = "To change your own password use Settings > Change password.";
	public static final String USER_TEMP_PASSWORD_REQUIRED = "Enter a temporary password, or choose to send an invitation email.";
	public static final String USER_INVITE_NOT_ALLOWED = "This user has already signed in. Use Reset password instead.";
	public static final String USER_LAST_ADMIN = "There must always be at least one active administrator.";

	// ==================== Two-step sign-in ====================
	public static final String MFA_NOT_AVAILABLE = "Two-step sign-in is not available on this server yet (MFA_ENCRYPTION_KEY is not set). Contact support.";
	public static final String MFA_ALREADY_ON = "Two-step sign-in is already on.";
	public static final String MFA_NOT_ON = "Two-step sign-in is not on.";
	public static final String MFA_SETUP_FIRST = "Start the set-up first, then enter the code from the app.";
	public static final String MFA_CODE_WRONG = "The code is not correct. Check the time on your phone and try the newest code.";
	public static final String MFA_CHALLENGE_INVALID = "Your sign-in step expired. Please sign in again with your password.";
	public static final String MFA_SERVER_KEY_PROBLEM = "Two-step codes cannot be checked right now. Use a recovery code, or contact support.";
	public static final String MFA_RESET_NOT_ON = "This user does not use two-step sign-in.";
	public static final String MFA_RESET_OWN = "To change your own two-step sign-in use Settings > Account security.";

	// ==================== Sign-in with Microsoft / Google ====================
	public static final String SSO_ALREADY_CONNECTED = "This account is already connected to an account of this provider. Disconnect it first.";
	public static final String SSO_CONNECTION_NOT_FOUND = "Connection not found.";
	public static final String SSO_TENANT_INVALID = "The Microsoft tenant id is a code like 72f988bf-86f1-41af-91ab-2d7cd011db47 (Microsoft Entra admin center > Overview).";
	public static final String SSO_PROVIDER_UNAVAILABLE = "This sign-in option is not set up on the server.";

	// ==================== Email outbox ====================
	public static final String EMAIL_NOT_FOUND = "Email not found.";
	public static final String EMAIL_RETRY_ONLY_FAILED = "Only failed emails can be sent again.";
	public static final String EMAIL_RETRY_ACCOUNT = "Invitation and password-reset emails contain one-time links and are not stored. Send a new invitation or reset instead.";

	// ==================== Platform Owner Messages ====================
	public static final String OWNER_UNIVERSITY_NOT_FOUND = "University not found.";
	public static final String OWNER_SLUG_INVALID = "The short code may only use lowercase letters, digits and dashes (3 to 60 characters), and must start and end with a letter or digit.";
	public static final String OWNER_SLUG_EXISTS = "Another university already uses this short code.";
	public static final String OWNER_NAME_EXISTS = "Another university already has this name.";
	public static final String OWNER_EMAIL_IN_OTHER_UNIVERSITY = "This email already belongs to an account. Use a different email.";
	public static final String OWNER_ADMIN_ALREADY_ACTIVE = "This administrator has already signed in. They can use \"Forgot password\" if needed.";


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
	public static final String ASSIGNMENT_BULK_MIXED_GROUPS = "Bulk save cannot mix assignments from different schedule groups.";
	public static final String ASSIGNMENT_GROUP_REQUIRED = "Assignment is missing a schedule group.";
	public static final String ASSIGNMENT_ROOM_OVERLAP = "Room ''{0}'' is already booked on {1} at an overlapping time ({2}).";
	public static final String ASSIGNMENT_CHIEF_ON_LEAVE = "Chief invigilator ''{0}'' is on leave on {1}{2}.";
	public static final String ASSIGNMENT_INVIGILATOR_ON_LEAVE = "Invigilator ''{0}'' is on leave on {1}{2}.";
	public static final String ASSIGNMENT_DAILY_LIMIT = "''{0}'' would exceed the limit of {1} duty session(s) on {2}.";
	public static final String ASSIGNMENT_GENDER_MISMATCH = "''{0}'' cannot supervise ''{1}'' — it is a {2}-only hall (set the person''s gender in People).";
	public static final String ASSIGNMENT_OWN_SUBJECT = "''{0}'' teaches {1} and cannot invigilate its exam.";
	public static final String ASSIGNMENT_REST_TOO_SHORT = "''{0}'' needs at least {1} minutes of rest between sessions on {2}.";
	public static final String ASSIGNMENT_PERIOD_LIMIT = "''{0}'' would exceed the limit of {1} duty session(s) in this exam period.";
	public static final String ASSIGNMENT_STUDENTS_EXCEED_CAPACITY = "Room ''{0}'' holds {1} students, but {2} were entered.";
	public static final String ASSIGNMENT_LOCKED_DELETE = "Room ''{0}'' on {1} is locked. Unlock it before removing it from the schedule.";
	public static final String ASSIGNMENT_NO_CHIEF_AVAILABLE = "No available chief invigilator for {0}.";
	public static final String ASSIGNMENT_INVIGILATORS_UNFILLED = "{0} invigilator slot(s) could not be filled for {1}.";
	public static final String SLOT_TIME_CHANGE_CONFLICT = "Changing this time slot would create a conflict: {0}";
	public static final String UNAVAILABILITY_INVALID_RANGE = "End date must be on or after start date.";
	public static final String UNAVAILABILITY_NOT_FOUND = "Unavailability period with id {0} was not found.";
	public static final String UNAVAILABILITY_CONFLICT = "''{0}'' already has assignments on: {1}. Reassign those duties first.";
	public static final String CONCURRENT_MODIFICATION = "This record was changed by another user at the same time. Reload and try again.";
	public static final String DATA_INTEGRITY_CONFLICT = "The change conflicts with existing data (duplicate or still-referenced record). Reload and try again.";
	public static final String SCHEDULE_BUSY = "The schedule is being updated by another user. Please try again in a moment.";
	public static final String SCHEDULE_BULK_ROW_ROOM_OVERLAP = "Room is already booked at an overlapping time on this date ({0}).";

	// ==================== Schedule Bulk Upload Messages ====================
	/** Returned when the uploaded workbook has no data rows to import. */
	public static final String SCHEDULE_BULK_UPLOAD_EMPTY = "The uploaded workbook contains headers only or no data rows.";
	/** Top-level message when one or more rows fail validation. Accepts the failed row count as argument 0. */
	public static final String SCHEDULE_BULK_UPLOAD_ROW_ERRORS = "Bulk schedule upload failed: {0} row(s) contain errors. No changes were saved.";
	/** Row error: the Date cell could not be parsed. */
	public static final String SCHEDULE_BULK_ROW_DATE_REQUIRED = "Date is required.";
	public static final String SCHEDULE_BULK_ROW_DATE_INVALID = "Invalid date ''{0}''. Expected format is YYYY-MM-DD.";
	/** Row error: the Time Slot cell could not be parsed or matched. */
	public static final String SCHEDULE_BULK_ROW_SLOT_REQUIRED = "Time Slot is required.";
	public static final String SCHEDULE_BULK_ROW_SLOT_INVALID_FORMAT = "Invalid time slot ''{0}''. Expected format is HH:mm - HH:mm, selected from the dropdown.";
	public static final String SCHEDULE_BULK_ROW_SLOT_NOT_FOUND = "Time slot ''{0}'' does not exist (or is inactive) for this schedule group.";
	/** Row error: the Room cell could not be matched. */
	public static final String SCHEDULE_BULK_ROW_ROOM_REQUIRED = "Room is required.";
	public static final String SCHEDULE_BULK_ROW_ROOM_NOT_FOUND = "Room ''{0}'' does not exist or is inactive.";
	/** Row error: subject name/code exceed the column length limits. */
	public static final String SCHEDULE_BULK_ROW_SUBJECT_NAME_TOO_LONG = "Subject Name must be at most 160 characters.";
	public static final String SCHEDULE_BULK_ROW_SUBJECT_CODE_TOO_LONG = "Subject Code must be at most 40 characters.";
	/** Row error: the same date/time slot/room triple appears more than once. */
	public static final String SCHEDULE_BULK_ROW_DUPLICATE_IN_FILE = "Duplicate row: the same date, time slot, and room already appears in row {0}.";
	/** Row error: an assignment already exists in the database for this date/time slot/room. */
	public static final String SCHEDULE_BULK_ROW_ALREADY_EXISTS = "An assignment already exists for this date, time slot, and room.";
	/** Returned when generating a template for a group that has no time slots yet. */
	public static final String SCHEDULE_BULK_TEMPLATE_NO_SLOTS = "This schedule group has no time slots yet. Create at least one time slot before generating a bulk upload template.";
	/** Returned when generating a template while no active rooms exist. */
	public static final String SCHEDULE_BULK_TEMPLATE_NO_ROOMS = "No active rooms exist. Add at least one active room before generating a bulk upload template.";
}

