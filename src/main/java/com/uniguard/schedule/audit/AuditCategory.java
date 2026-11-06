package com.uniguard.schedule.audit;

/** Groups used to filter the activity log. */
public enum AuditCategory {
	/** Sign-in, sign-out, passwords. */
	AUTH,
	/** Suspicious or refused requests: access denied, forged requests, stolen session tokens. */
	SECURITY,
	USERS,
	PEOPLE,
	ROOMS,
	TIME_SLOTS,
	SCHEDULES,
	SETTINGS,
	/** Platform owner: universities created, switched off or on, first administrators. */
	PLATFORM,
	/** Anything else that changed data (a new endpoint without its own label yet). */
	OTHER
}
